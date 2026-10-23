'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinancePaymentOrderCapabilities,
  FinancePaymentOrderDetail,
  FinancePaymentOrderOptions,
  FinancePaymentOrderPage,
  FinancePaymentPayableLineOption,
} from '../../../../../packages/api-contracts/src/index';
import {
  approvalActionLabel,
  buildPaymentOrderInput,
  createPaymentOrder,
  fetchPaymentExchangeRate,
  fetchPaymentOrderCapabilities,
  fetchPaymentOrderDetail,
  fetchPaymentOrderOptions,
  fetchPaymentOrders,
  fetchPayableLines,
  formatPaymentOrderAmount,
  formatPaymentOrderMoney,
  installmentStatusLabel,
  paymentAccountLabel,
  paymentApprovalStatusLabel,
  paymentApprovalStatusTone,
  paymentCounterpartyTypeLabel,
  paymentExecutionLabel,
  paymentExchangeRateHint,
  paymentMethodLabel,
  paymentOrderEventLabel,
  paymentOrderStatusLabel,
  paymentOrderStatusTone,
  payableLineBlockedReason,
  payableLineSelectable,
  submitPaymentOrder,
  syncPaymentOrderApproval,
} from './payment-order-model';

/**
 * 12.2.2.1 付款单与审批。
 *
 * <p>蓝图 12-finance.md：「<b>发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、
 * 分批安排，走分级审批。</b>输入：付款申请；审批规则（金额阈值）；汇率快照。
 * 输出：<b>付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录</b>。
 * 关系：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
 * <b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「<b>选择应付明细、付款账户、币种与汇率、付款方式、分批安排</b>」⇒ 页面第一块是
 *       <b>发起付款申请</b>：读应付单的明细行（带未付余额与占用情况）→ 勾选 →
 *       填付款方式 / 账户 / 币种与汇率 → 排分批。</li>
 *   <li>「<b>走分级审批</b>」⇒ 页面提供<b>提交审批</b>与<b>同步审批结果</b>两个动作，
 *       并把审批链路（谁批的、批到哪一步、在哪个节点压了多久）显示出来。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的两个东西</b>：
 *
 * <ol>
 *   <li><b>任何「执行付款」「标记已付」的按钮</b>：付款属 12.2.2.2（未交付）。
 *       页面里的「已批」只表示<b>审批通过了</b>，<b>不是</b>钱已经付出去了。</li>
 *   <li><b>任何「审批通过 / 审批驳回」的按钮</b>：审批属 17 审批（identity）。
 *       审批动作要去 identity 的待我审批里做；这里只提交、只读回。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有付款单」。
 */
export function PaymentOrderWorkspace() {
  const [caps, setCaps] = useState<FinancePaymentOrderCapabilities | null>(null);
  const [options, setOptions] = useState<FinancePaymentOrderOptions | null>(null);
  const [page, setPage] = useState<FinancePaymentOrderPage | null>(null);
  const [detail, setDetail] = useState<FinancePaymentOrderDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [approvalStatus, setApprovalStatus] = useState('');
  const [currency, setCurrency] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 发起付款申请
  const [payableId, setPayableId] = useState('');
  const [payableLines, setPayableLines] = useState<FinancePaymentPayableLineOption[]>([]);
  const [selectedLines, setSelectedLines] = useState<string[]>([]);
  const [lineAmounts, setLineAmounts] = useState<Record<string, string>>({});
  const [orderCurrency, setOrderCurrency] = useState('');
  const [orderAmount, setOrderAmount] = useState('');
  const [method, setMethod] = useState('BANK_TRANSFER');
  const [accountName, setAccountName] = useState('');
  const [baseCurrency, setBaseCurrency] = useState('');
  const [exchangeRate, setExchangeRate] = useState('');
  const [rateHint, setRateHint] = useState('');
  const [installmentDate, setInstallmentDate] = useState('');
  const [installmentAmount, setInstallmentAmount] = useState('');
  const [note, setNote] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(approvalStatus ? { approvalStatus } : {}),
    ...(currency ? { currency } : {}),
    ...(paymentMethod ? { paymentMethod } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p] = await Promise.all([
        fetchPaymentOrderCapabilities(),
        fetchPaymentOrderOptions(),
        fetchPaymentOrders(params),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : '付款单加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, approvalStatus, currency, paymentMethod, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 读一张应付单的明细行（带未付余额与占用情况）。 */
  async function loadPayableLines() {
    setActionError('');
    setNotice('');
    try {
      const lines = await fetchPayableLines(payableId.trim());
      setPayableLines(lines);
      setSelectedLines([]);
      setLineAmounts({});
      setOrderCurrency(lines[0]?.currency ?? '');
      setNotice(`已载入 ${lines.length} 条应付明细`);
    } catch (e) {
      setPayableLines([]);
      setActionError(e instanceof Error ? e.message : '应付明细读取失败');
    }
  }

  /** 拉一次汇率快照（**读** catalog 5.2.2.1，结果原样显示）。 */
  async function loadRate() {
    setActionError('');
    setRateHint('');
    try {
      const rate = await fetchPaymentExchangeRate(
        orderCurrency.trim(),
        baseCurrency.trim(),
      );
      setExchangeRate(String(rate.rate));
      setRateHint(paymentExchangeRateHint(rate));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '汇率快照读取失败');
    }
  }

  /** 勾选 / 取消勾选一条应付明细。 */
  function toggleLine(line: FinancePaymentPayableLineOption) {
    setSelectedLines((prev) =>
      prev.includes(line.payableLineId)
        ? prev.filter((id) => id !== line.payableLineId)
        : [...prev, line.payableLineId],
    );
  }

  /** 发起付款申请（建草稿）。 */
  async function runCreate() {
    setActionError('');
    setNotice('');
    try {
      const lines = selectedLines.map((id) => {
        const found = payableLines.find((l) => l.payableLineId === id);
        return {
          payableLineId: id,
          description: found?.description ?? null,
          currency: found?.currency ?? orderCurrency,
          amount: lineAmounts[id] ?? found?.unpaidAmount.toString() ?? '0',
        };
      });
      const installments = installmentDate && installmentAmount
        ? [{ plannedPaidDate: installmentDate, currency: orderCurrency, amount: installmentAmount }]
        : [];
      const first = payableLines.find((l) => selectedLines.includes(l.payableLineId));
      const order = await createPaymentOrder(
        buildPaymentOrderInput({
          counterpartyType: 'SUPPLIER',
          counterpartyRef: null,
          counterpartyName: first?.counterpartyName ?? null,
          currency: orderCurrency,
          amount: orderAmount,
          baseCurrency: baseCurrency || null,
          exchangeRate: exchangeRate || null,
          paymentMethod: method as 'BANK_TRANSFER',
          paymentAccountId: null,
          paymentAccountName: accountName || null,
          paymentAccountCurrency: null,
          paymentPlanId: null,
          note: note || null,
          lines,
          installments,
        }),
      );
      setNotice(`已建付款单 ${order.orderNo}（草稿）`);
      setPayableLines([]);
      setSelectedLines([]);
      setOrderAmount('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '建单失败');
    }
  }

  /** 提交审批（草稿 → 待审）。 */
  async function runSubmit(id: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await submitPaymentOrder(id);
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '提交审批失败');
    }
  }

  /** 同步审批结果（读回 identity 的审批链路）。 */
  async function runSync(id: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await syncPaymentOrderApproval(id);
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '同步审批结果失败');
    }
  }

  /** 打开一张付款单的详情。 */
  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchPaymentOrderDetail(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '付款单详情读取失败');
    }
  }

  const gaps = caps?.downstreamGaps ?? [];
  const unavailable = (caps?.capabilities ?? []).filter((c) => !c.available);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="付款单与审批"
        description="发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、分批安排，走分级审批。审批通过不等于钱已付出 —— 付款执行属 12.2.2.2（未交付）。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 未交付的下游（如实说清归谁） ---------- */}
          {gaps.length > 0 && (
            <Notice tone="warning" title="未交付的下游">
              {gaps.map((gap) => `${gap.module} ${gap.name}：${gap.detail}`).join('；')}
            </Notice>
          )}
          {unavailable.length > 0 && (
            <Notice tone="warning" title="本模块当前不可用的能力">
              {unavailable
                .map((c) => `${c.name}（归属 ${c.ownerModule}）：${c.detail}`)
                .join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="付款单" value={page?.total ?? 0} unit="张" />
            <StatCard label="待审" value={page?.pendingApprovalCount ?? 0} unit="张" />
            <StatCard
              label="币种"
              value={page?.summaryByCurrency.length ?? 0}
              unit="种"
              comparisonLabel="按币种分开统计，绝不相加"
            />
            <StatCard
              label="已批待执行"
              value={
                page?.summaryByCurrency.reduce((sum, s) => sum + s.approvedCount, 0) ?? 0
              }
              unit="张"
              comparisonLabel="已批 ≠ 已付（12.2.2.2 未交付）"
            />
          </div>

          {/* ---------- 发起付款申请 ---------- */}
          <SectionCard
            title="发起付款申请"
            description="先读应付单的明细行（带未付余额与占用情况），再勾选要付的行。单头金额必须等于明细合计、分批合计 —— 这条由服务端校验，页面不做任何前端算术。"
          >
            <div className="space-y-4">
              <div className="grid gap-2 md:grid-cols-4">
                <Input
                  value={payableId}
                  aria-label="应付单 ID"
                  placeholder="应付单 ID"
                  onChange={(e) => setPayableId(e.target.value)}
                />
                <Input
                  value={orderCurrency}
                  aria-label="付款币种"
                  placeholder="付款币种（USD）"
                  onChange={(e) => setOrderCurrency(e.target.value.toUpperCase())}
                />
                <Input
                  value={orderAmount}
                  aria-label="付款单金额"
                  placeholder="付款单金额"
                  onChange={(e) => setOrderAmount(e.target.value)}
                />
                <Button variant="secondary" onClick={() => void loadPayableLines()}>
                  读取应付明细
                </Button>
              </div>

              {payableLines.length > 0 && (
                <div>
                  <p className="text-copy-13 mb-2 font-medium">应付明细（{payableLines.length} 条）</p>
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">选</th>
                        <th className="py-2">明细</th>
                        <th className="py-2">币种</th>
                        <th className="py-2">未付余额</th>
                        <th className="py-2">本次付款</th>
                        <th className="py-2">状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {payableLines.map((line) => (
                        <tr key={line.payableLineId} className="border-border border-t">
                          <td className="py-2">
                            <input
                              type="checkbox"
                              aria-label={`选择明细 ${line.lineNo}`}
                              disabled={!payableLineSelectable(line)}
                              checked={selectedLines.includes(line.payableLineId)}
                              onChange={() => toggleLine(line)}
                            />
                          </td>
                          <td className="py-2">
                            {line.description || `第 ${line.lineNo} 行`}
                            <span className="text-muted-foreground ml-2">{line.payableNo}</span>
                          </td>
                          <td className="py-2">{line.currency}</td>
                          <td className="py-2">{formatPaymentOrderAmount(line.unpaidAmount)}</td>
                          <td className="py-2">
                            <Input
                              value={
                                lineAmounts[line.payableLineId] ?? line.unpaidAmount.toString()
                              }
                              aria-label={`明细 ${line.lineNo} 的付款金额`}
                              disabled={!selectedLines.includes(line.payableLineId)}
                              onChange={(e) =>
                                setLineAmounts((prev) => ({
                                  ...prev,
                                  [line.payableLineId]: e.target.value,
                                }))
                              }
                            />
                          </td>
                          <td className="py-2">
                            {payableLineSelectable(line) ? (
                              <Badge tone="success">可付款</Badge>
                            ) : (
                              <Badge tone="neutral">{payableLineBlockedReason(line)}</Badge>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              <div className="grid gap-2 md:grid-cols-4">
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={method}
                  aria-label="付款方式"
                  onChange={(e) => setMethod(e.target.value)}
                >
                  <option value="BANK_TRANSFER">银行电汇</option>
                  <option value="PAYPAL">PayPal</option>
                  <option value="STRIPE">Stripe</option>
                  <option value="PINGPONG">PingPong</option>
                  <option value="OTHER">其他</option>
                </select>
                <Input
                  value={accountName}
                  aria-label="付款账户名称"
                  placeholder="付款账户名称（资金账户 12.7.1.1 未交付，只登记引用）"
                  onChange={(e) => setAccountName(e.target.value)}
                />
                <Input
                  value={baseCurrency}
                  aria-label="本位币"
                  placeholder="本位币（可选）"
                  onChange={(e) => setBaseCurrency(e.target.value.toUpperCase())}
                />
                <Input
                  value={exchangeRate}
                  aria-label="汇率快照"
                  placeholder="汇率快照（可选）"
                  onChange={(e) => setExchangeRate(e.target.value)}
                />
              </div>
              {rateHint && (
                <p className="text-copy-13 text-muted-foreground">汇率快照：{rateHint}</p>
              )}
              <div className="grid gap-2 md:grid-cols-4">
                <Button variant="secondary" onClick={() => void loadRate()}>
                  拉取汇率快照
                </Button>
                <Input
                  value={installmentDate}
                  aria-label="分批计划付款日"
                  placeholder="分批付款日（可选）"
                  onChange={(e) => setInstallmentDate(e.target.value)}
                />
                <Input
                  value={installmentAmount}
                  aria-label="分批金额"
                  placeholder="分批金额（可选）"
                  onChange={(e) => setInstallmentAmount(e.target.value)}
                />
                <Input
                  value={note}
                  aria-label="备注"
                  placeholder="备注"
                  onChange={(e) => setNote(e.target.value)}
                />
              </div>
              <div>
                <Button
                  onClick={() => void runCreate()}
                  disabled={selectedLines.length === 0 || !orderAmount}
                >
                  建付款单（草稿）
                </Button>
              </div>
            </div>
          </SectionCard>

          {/* ---------- 付款单清单 ---------- */}
          <SectionCard title="付款单" description="真分页 + 真筛选；汇总覆盖整个筛选结果集。">
            <div className="mb-4 grid gap-2 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                aria-label="付款单状态"
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'EXECUTING', 'PAID', 'FAILED'] as const).map(
                  (value) => (
                    <option key={value} value={value}>
                      {paymentOrderStatusLabel(value)}
                    </option>
                  ),
                )}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={approvalStatus}
                aria-label="审批状态"
                onChange={(e) => {
                  setApprovalStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部审批状态</option>
                {(['NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED'] as const).map((value) => (
                  <option key={value} value={value}>
                    {paymentApprovalStatusLabel(value)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                aria-label="币种"
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={paymentMethod}
                aria-label="付款方式"
                onChange={(e) => {
                  setPaymentMethod(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部付款方式</option>
                {(options?.paymentMethods ?? []).map((value) => (
                  <option key={value} value={value}>
                    {paymentMethodLabel(value)}
                  </option>
                ))}
              </select>
              <Input
                value={keyword}
                aria-label="关键词"
                placeholder="关键词（单号 / 付款对象 / 备注）"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={keyword || status || approvalStatus ? '没有符合条件的付款单' : '还没有付款单'}
                description={
                  keyword || status || approvalStatus
                    ? '换个筛选条件再看看。'
                    : '在上面「发起付款申请」里选应付明细建一张草稿。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">单号</th>
                    <th className="py-2">付款对象</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">付款方式</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">审批</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((order) => (
                    <tr key={order.id} className="border-border border-t">
                      <td className="py-2 font-medium">{order.orderNo}</td>
                      <td className="py-2">
                        {order.counterpartyName || '—'}
                        <span className="text-muted-foreground ml-2">
                          {paymentCounterpartyTypeLabel(order.counterpartyType)}
                        </span>
                      </td>
                      <td className="py-2">{formatPaymentOrderMoney(order.amount, order.currency)}</td>
                      <td className="py-2">{paymentMethodLabel(order.paymentMethod)}</td>
                      <td className="py-2">
                        <Badge tone={paymentOrderStatusTone(order.status)}>
                          {paymentOrderStatusLabel(order.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        <Badge tone={paymentApprovalStatusTone(order.approvalStatus)}>
                          {paymentApprovalStatusLabel(order.approvalStatus)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap gap-2">
                          <Button variant="secondary" onClick={() => void openDetail(order.id)}>
                            详情
                          </Button>
                          {order.status === 'DRAFT' && (
                            <Button onClick={() => void runSubmit(order.id)}>提交审批</Button>
                          )}
                          {order.status === 'PENDING_APPROVAL' && (
                            <Button
                              variant="secondary"
                              onClick={() => void runSync(order.id)}
                            >
                              同步审批结果
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {page && page.total > 0 && (
              <div className="mt-3 flex items-center justify-between">
                <p className="text-muted-foreground text-copy-13">
                  第 {page.page + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页 · 共{' '}
                  {page.total} 张
                </p>
                <div className="flex gap-2">
                  <Button
                    variant="secondary"
                    disabled={currentPage === 0}
                    onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={(currentPage + 1) * page.size >= page.total}
                    onClick={() => setCurrentPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            )}

            {page && page.summaryByCurrency.length > 0 && (
              <div className="mt-4">
                <p className="text-copy-13 mb-2 font-medium">按币种汇总（绝不跨币种相加）</p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">币种</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">张数</th>
                      <th className="py-2">草稿</th>
                      <th className="py-2">待审</th>
                      <th className="py-2">已批</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.summaryByCurrency.map((summary) => (
                      <tr key={summary.currency} className="border-border border-t">
                        <td className="py-2 font-medium">{summary.currency}</td>
                        <td className="py-2">{formatPaymentOrderAmount(summary.amount)}</td>
                        <td className="py-2">{summary.count}</td>
                        <td className="py-2">{summary.draftCount}</td>
                        <td className="py-2">{summary.pendingApprovalCount}</td>
                        <td className="py-2">{summary.approvedCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---------- 付款单详情 ---------- */}
          {detail && (
            <SectionCard
              title={`付款单 ${detail.order.orderNo}`}
              description="明细精确到应付明细行；审批记录是 17 审批链路的本地镜像（动作本身在 identity 侧完成）。"
              extra={
                <div className="flex gap-2">
                  {detail.order.status === 'DRAFT' && (
                    <Button onClick={() => void runSubmit(detail.order.id)}>提交审批</Button>
                  )}
                  {detail.order.status === 'PENDING_APPROVAL' && (
                    <Button variant="secondary" onClick={() => void runSync(detail.order.id)}>
                      同步审批结果
                    </Button>
                  )}
                </div>
              }
            >
              <div className="space-y-4">
                <div className="grid gap-2 md:grid-cols-4">
                  <div>
                    <p className="text-muted-foreground text-copy-13">金额</p>
                    <p className="font-medium">
                      {formatPaymentOrderMoney(detail.order.amount, detail.order.currency)}
                    </p>
                  </div>
                  <div>
                    <p className="text-muted-foreground text-copy-13">汇率快照</p>
                    <p className="font-medium">
                      {detail.order.exchangeRate
                        ? `1 ${detail.order.currency} = ${detail.order.exchangeRate} ${detail.order.baseCurrency}（本位币 ${formatPaymentOrderAmount(detail.order.baseAmount)}）`
                        : '未使用（付款币种即本位币）'}
                    </p>
                  </div>
                  <div>
                    <p className="text-muted-foreground text-copy-13">付款账户</p>
                    <p className="font-medium">
                      {paymentAccountLabel(
                        detail.order.paymentAccountName,
                        detail.order.paymentAccountOwnerModule,
                      )}
                    </p>
                  </div>
                  <div>
                    <p className="text-muted-foreground text-copy-13">付款执行</p>
                    <p className="font-medium">
                      {paymentExecutionLabel(
                        detail.order.executionAvailable,
                        detail.order.executionOwnerModule,
                      )}
                    </p>
                  </div>
                </div>

                <div>
                  <p className="text-copy-13 mb-2 font-medium">
                    付款明细（{detail.lines.length} 行 · 精确到应付明细行）
                  </p>
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">#</th>
                        <th className="py-2">应付单</th>
                        <th className="py-2">说明</th>
                        <th className="py-2">币种</th>
                        <th className="py-2">付款金额</th>
                        <th className="py-2">应付未付快照</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.lines.map((line) => (
                        <tr key={line.id} className="border-border border-t">
                          <td className="py-2">{line.lineNo}</td>
                          <td className="py-2">{line.payableNo}</td>
                          <td className="py-2">{line.description || '—'}</td>
                          <td className="py-2">{line.currency}</td>
                          <td className="py-2">{formatPaymentOrderAmount(line.amount)}</td>
                          <td className="py-2">
                            {formatPaymentOrderAmount(
                              Number(line.payableAmountSnapshot) - Number(line.payablePaidSnapshot),
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div>
                  <p className="text-copy-13 mb-2 font-medium">
                    分批安排（{detail.installments.length} 批）
                  </p>
                  {detail.installments.length === 0 ? (
                    <p className="text-muted-foreground text-copy-13">
                      未分批 —— 这笔付款一次性付出。
                    </p>
                  ) : (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">#</th>
                          <th className="py-2">计划付款日</th>
                          <th className="py-2">币种</th>
                          <th className="py-2">金额</th>
                          <th className="py-2">状态</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.installments.map((item) => (
                          <tr key={item.id} className="border-border border-t">
                            <td className="py-2">{item.seq}</td>
                            <td className="py-2">{item.plannedPaidDate}</td>
                            <td className="py-2">{item.currency}</td>
                            <td className="py-2">{formatPaymentOrderAmount(item.amount)}</td>
                            <td className="py-2">{installmentStatusLabel(item.status)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>

                <div>
                  <p className="text-copy-13 mb-2 font-medium">
                    审批记录（{detail.approvalRecords.length} 条 · 镜像自 17 审批）
                  </p>
                  {detail.approvalRecords.length === 0 ? (
                    <p className="text-muted-foreground text-copy-13">
                      还没有提交审批 —— 提交后这里会显示真实的审批链路。
                    </p>
                  ) : (
                    <ul className="text-copy-13 space-y-1">
                      {detail.approvalRecords.map((record) => (
                        <li key={record.id} className="border-border rounded-md border p-2">
                          <span className="font-medium">{approvalActionLabel(record.action)}</span>
                          <span className="text-muted-foreground ml-2">
                            {record.nodeName || record.nodeKey} · {record.actorName || '—'} ·{' '}
                            {record.decidedAt}
                          </span>
                          {record.comment && (
                            <span className="text-muted-foreground ml-2">「{record.comment}」</span>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                <div>
                  <p className="text-copy-13 mb-2 font-medium">事件流（{detail.events.length} 条）</p>
                  <ul className="text-copy-13 space-y-1">
                    {detail.events.map((event) => (
                      <li key={event.id}>
                        {paymentOrderEventLabel(event.eventType)}
                        {event.reason && (
                          <span className="text-muted-foreground ml-2">「{event.reason}」</span>
                        )}
                        <span className="text-muted-foreground ml-2">{event.occurredAt}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
