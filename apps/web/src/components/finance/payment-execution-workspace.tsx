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
  FinancePaymentChannelRow,
  FinancePaymentExecutionCapabilities,
  FinancePaymentExecutionDetail,
  FinancePaymentExecutionOptions,
  FinancePaymentExecutionPage,
  FinancePaymentExecutionPendingOrder,
} from '../../../../../packages/api-contracts/src/index';
import {
  executePayment,
  fetchPaymentChannels,
  fetchPaymentExecutionCapabilities,
  fetchPaymentExecutionDetail,
  fetchPaymentExecutionOptions,
  fetchPaymentExecutions,
  fetchPendingPaymentOrders,
  formatPaymentExchangeRate,
  formatPaymentExecutionAmount,
  paymentAttemptKindLabel,
  paymentAttemptOutcomeLabel,
  paymentAttemptOutcomeTone,
  paymentExecutionActions,
  paymentExecutionEventLabel,
  paymentExecutionMethodLabel,
  paymentExecutionStatusLabel,
  paymentExecutionStatusTone,
  paymentFailureClassLabel,
  paymentRateLockStatusLabel,
  pendingPaymentOrderLabel,
  registerPaymentChannel,
  retryPaymentExecution,
  verifyPaymentExecution,
} from './payment-execution-model';

/**
 * 12.2.2.2 付款执行。
 *
 * <p>蓝图 12-finance.md：「<b>执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
 * 失败重试与异常处理；处理汇率锁汇。</b>输入：已批付款单；支付渠道配置（密钥引用）；汇率锁定。
 * 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。关系：上游 = 12.2.2.1。
 * <b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「<b>对接支付渠道/银企直连，发送付款指令，跟踪结果</b>」⇒ 页面第一块是
 *       <b>发起付款执行</b>（选已批付款单 + 选渠道 → 发送），第二块是执行单表
 *       （真分页 / 真筛选 / 按币种分开汇总），第三块是执行单详情
 *       （尝试记录 / 事件流 / 分摊 / 锁汇）；</li>
 *   <li>「<b>失败重试与异常处理</b>」⇒ 详情里给出<b>查证</b>与<b>重发</b>两个动作，
 *       并且明确告诉用户「查证发现已成功时**不会**重发」。</li>
 * </ol>
 *
 * <p>🔴 <b>「结果待查证」在页面上刻意不是红色</b>：外部支付超时不等于失败，
 * 标红会让人去点重试 —— 而重复付款是严重资损事故。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有执行单」。
 */
export function PaymentExecutionWorkspace() {
  const [caps, setCaps] = useState<FinancePaymentExecutionCapabilities | null>(null);
  const [options, setOptions] = useState<FinancePaymentExecutionOptions | null>(null);
  const [page, setPage] = useState<FinancePaymentExecutionPage | null>(null);
  const [detail, setDetail] = useState<FinancePaymentExecutionDetail | null>(null);
  const [channels, setChannels] = useState<FinancePaymentChannelRow[]>([]);
  const [pending, setPending] = useState<FinancePaymentExecutionPendingOrder[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [failureClass, setFailureClass] = useState('');
  const [currency, setCurrency] = useState('');
  const [channelCode, setChannelCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 发起付款执行
  const [pendingKey, setPendingKey] = useState('');
  const [executeChannel, setExecuteChannel] = useState('');
  const [executeNote, setExecuteNote] = useState('');

  // 渠道登记
  const [channelCodeInput, setChannelCodeInput] = useState('');
  const [channelName, setChannelName] = useState('');
  const [channelMethod, setChannelMethod] = useState('BANK_TRANSFER');
  const [channelBaseUrl, setChannelBaseUrl] = useState('');
  const [channelSecretRef, setChannelSecretRef] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(failureClass ? { failureClass } : {}),
    ...(currency ? { currency } : {}),
    ...(channelCode ? { channelCode } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, ch, po] = await Promise.all([
        fetchPaymentExecutionCapabilities(),
        fetchPaymentExecutionOptions(),
        fetchPaymentExecutions(params),
        fetchPaymentChannels(),
        fetchPendingPaymentOrders(),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setChannels(ch);
      setPending(po);
    } catch (e) {
      setError(e instanceof Error ? e.message : '付款执行加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, failureClass, currency, channelCode, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开一张执行单的详情。 */
  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchPaymentExecutionDetail(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '执行单详情读取失败');
    }
  }

  /** 发起一次付款执行。 */
  async function runExecute() {
    setActionError('');
    setNotice('');
    const target = pending.find((item) => pendingKeyOf(item) === pendingKey);
    if (!target) {
      setActionError('请先选择要执行的付款单');
      return;
    }
    if (!executeChannel) {
      setActionError('请先选择支付渠道');
      return;
    }
    try {
      const result = await executePayment({
        orderId: target.id,
        installmentId: target.installmentId,
        channelCode: executeChannel,
        note: executeNote || null,
      });
      setNotice(
        `${result.message}${result.verified ? '（本次是状态查证，不是重发）' : ''}${
          result.resent ? '' : ''
        }`,
      );
      setExecuteNote('');
      await load();
      await openDetail(result.execution.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '发起付款执行失败');
    }
  }

  /** 状态查证（**不重发**）。 */
  async function runVerify(id: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await verifyPaymentExecution(id);
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '状态查证失败');
    }
  }

  /**
   * 重发。
   *
   * <p>⚠️ 后端**先查证再重发**：查证发现渠道侧已成功时它不会重发，
   * 页面如实把后端给的结论显示出来。
   */
  async function runRetry(id: string) {
    setActionError('');
    setNotice('');
    try {
      const result = await retryPaymentExecution(id);
      setNotice(result.message);
      await load();
      await openDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '重发失败');
    }
  }

  /** 登记一个支付渠道（凭据只存**引用**）。 */
  async function runRegisterChannel() {
    setActionError('');
    setNotice('');
    try {
      const created = await registerPaymentChannel({
        code: channelCodeInput.trim(),
        name: channelName.trim(),
        paymentMethod: channelMethod,
        baseUrl: channelBaseUrl.trim(),
        secretRef: channelSecretRef.trim(),
        enabled: true,
        timeoutSeconds: null,
        maxAttempts: null,
        note: null,
      });
      setNotice(`已登记渠道 ${created.code}（凭据只存引用 ${created.secretRef}）`);
      setChannelCodeInput('');
      setChannelName('');
      setChannelBaseUrl('');
      setChannelSecretRef('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '渠道登记失败');
    }
  }

  const gaps = caps?.downstreamGaps ?? [];
  const unavailable = (caps?.capabilities ?? []).filter((c) => !c.available);
  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const actions = detail ? paymentExecutionActions(detail.execution.status) : null;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="付款执行"
        description="对接支付渠道/银企直连，发送付款指令，跟踪结果，失败重试与异常处理；处理汇率锁汇。外部支付超时不等于失败 —— 必须先查证再重发，重复付款是严重资损事故。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {actionError && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}
      {notice && (
        <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm">
          {notice}
        </div>
      )}

      {unavailable.length > 0 && (
        <Notice tone="warning" title="以下能力尚未交付（归属模块已写明）">
          <ul className="list-disc space-y-1 pl-5">
            {unavailable.map((c) => (
              <li key={`${c.module}-${c.name}`}>
                {c.module} {c.name} —— {c.detail}
              </li>
            ))}
          </ul>
        </Notice>
      )}
      {gaps.length > 0 && (
        <Notice tone="info" title="下游缺口">
          <ul className="list-disc space-y-1 pl-5">
            {gaps.map((g) => (
              <li key={`${g.module}-${g.name}`}>
                {g.module} {g.name}（{g.ownerService}）—— {g.detail}
              </li>
            ))}
          </ul>
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="执行单总数" value={page?.total ?? 0} />
        <StatCard
          label="结果待查证"
          value={page?.unknownCount ?? 0}
          polarity="negative"
          icon={<TriangleAlert className="h-4 w-4" />}
        />
        <StatCard
          label="已登记渠道"
          value={channels.length}
          polarity="neutral"
        />
        <StatCard
          label="可发起付款的付款单"
          value={pending.length}
          polarity="neutral"
        />
      </div>

      <SectionCard
        title="发起付款执行"
        description="只列已批 / 执行中的付款单；有分批安排的逐批出现。渠道没有凭据时服务端会明确拒绝，不会假装成功。"
      >
        {pending.length === 0 ? (
          <EmptyState
            title="没有可执行的付款单"
            description="当前租户没有处于「已批」或「执行中」的付款单。先在 12.2.2.1 付款单与审批里走完审批。"
          />
        ) : (
          <div className="space-y-4">
            <label className="block text-sm">
              <span className="mb-1 block text-muted-foreground">付款单</span>
              <select
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                value={pendingKey}
                onChange={(e) => setPendingKey(e.target.value)}
              >
                <option value="">请选择…</option>
                {pending.map((item) => (
                  <option key={pendingKeyOf(item)} value={pendingKeyOf(item)}>
                    {pendingPaymentOrderLabel(item)}
                    {item.hasExecution ? `（已有执行单 ${item.executionNo}）` : ''}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="mb-1 block text-muted-foreground">支付渠道</span>
              <select
                className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
                value={executeChannel}
                onChange={(e) => setExecuteChannel(e.target.value)}
              >
                <option value="">请选择…</option>
                {channels.map((c) => (
                  <option key={c.id} value={c.code} disabled={!c.enabled}>
                    {c.name}（{c.code} · {paymentExecutionMethodLabel(c.paymentMethod)}
                    {c.enabled ? '' : ' · 已停用'}）
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="mb-1 block text-muted-foreground">备注</span>
              <Input
                value={executeNote}
                onChange={(e) => setExecuteNote(e.target.value)}
                placeholder="例如：10 月供应商结算第一批"
              />
            </label>
            <Button onClick={() => void runExecute()} disabled={loading}>
              发送付款指令
            </Button>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="支付渠道配置"
        description="凭据只存**引用**（secret manager 的引用 ID），本系统不保存任何明文密钥。"
      >
        {channels.length === 0 ? (
          <EmptyState title="还没有支付渠道" description="登记一个渠道后才能发起付款执行。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">编码</th>
                  <th className="py-2 pr-3">名称</th>
                  <th className="py-2 pr-3">付款方式</th>
                  <th className="py-2 pr-3">地址</th>
                  <th className="py-2 pr-3">凭据引用</th>
                  <th className="py-2 pr-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {channels.map((c) => (
                  <tr key={c.id} className="border-t border-border">
                    <td className="py-2 pr-3">{c.code}</td>
                    <td className="py-2 pr-3">{c.name}</td>
                    <td className="py-2 pr-3">{paymentExecutionMethodLabel(c.paymentMethod)}</td>
                    <td className="py-2 pr-3">{c.baseUrl}</td>
                    <td className="py-2 pr-3 font-mono text-xs">{c.secretRef}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={c.enabled ? 'success' : 'neutral'}>
                        {c.enabled ? '已启用' : '已停用'}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Input
            value={channelCodeInput}
            onChange={(e) => setChannelCodeInput(e.target.value)}
            placeholder="渠道编码（如 BANK_HB）"
          />
          <Input
            value={channelName}
            onChange={(e) => setChannelName(e.target.value)}
            placeholder="渠道名称"
          />
          <select
            className="rounded-md border border-border bg-background px-3 py-2 text-sm"
            value={channelMethod}
            onChange={(e) => setChannelMethod(e.target.value)}
          >
            <option value="BANK_TRANSFER">银行电汇</option>
            <option value="PAYPAL">PayPal</option>
            <option value="STRIPE">Stripe</option>
            <option value="PINGPONG">PingPong</option>
            <option value="OTHER">其他</option>
          </select>
          <Input
            value={channelBaseUrl}
            onChange={(e) => setChannelBaseUrl(e.target.value)}
            placeholder="渠道地址（https://…）"
          />
          <Input
            value={channelSecretRef}
            onChange={(e) => setChannelSecretRef(e.target.value)}
            placeholder="凭据引用（如 vault://starcon/finance/…）"
          />
          <Button variant="secondary" onClick={() => void runRegisterChannel()}>
            登记渠道
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="付款执行单"
        description="真分页 + 真筛选；汇总按币种分开（不同币种不加在一起）。"
      >
        <div className="mb-4 grid gap-3 md:grid-cols-5">
          <select
            className="rounded-md border border-border bg-background px-3 py-2 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setCurrentPage(0);
            }}
          >
            <option value="">全部状态</option>
            {(options?.statuses ?? []).map((s) => (
              <option key={s} value={s}>
                {paymentExecutionStatusLabel(s)}
              </option>
            ))}
          </select>
          <select
            className="rounded-md border border-border bg-background px-3 py-2 text-sm"
            value={failureClass}
            onChange={(e) => {
              setFailureClass(e.target.value);
              setCurrentPage(0);
            }}
          >
            <option value="">全部失败分类</option>
            {(options?.failureClasses ?? []).map((s) => (
              <option key={s} value={s}>
                {paymentFailureClassLabel(s)}
              </option>
            ))}
          </select>
          <select
            className="rounded-md border border-border bg-background px-3 py-2 text-sm"
            value={currency}
            onChange={(e) => {
              setCurrency(e.target.value);
              setCurrentPage(0);
            }}
          >
            <option value="">全部币种</option>
            {(options?.currencies ?? []).map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          <select
            className="rounded-md border border-border bg-background px-3 py-2 text-sm"
            value={channelCode}
            onChange={(e) => {
              setChannelCode(e.target.value);
              setCurrentPage(0);
            }}
          >
            <option value="">全部渠道</option>
            {(options?.channels ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <Input
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setCurrentPage(0);
            }}
            placeholder="执行单号 / 渠道流水号 / 幂等键"
          />
        </div>

        {loading && !page ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            title={status || failureClass || currency || channelCode || keyword ? '没有符合条件的执行单' : '还没有付款执行记录'}
            description={
              status || failureClass || currency || channelCode || keyword
                ? '换个筛选条件再看看。'
                : '付款单审批通过后，在这里发起付款执行。'
            }
          />
        ) : (
          page && (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-muted-foreground">
                    <tr>
                      <th className="py-2 pr-3">执行单号</th>
                      <th className="py-2 pr-3">付款单</th>
                      <th className="py-2 pr-3">渠道</th>
                      <th className="py-2 pr-3">金额</th>
                      <th className="py-2 pr-3">状态</th>
                      <th className="py-2 pr-3">渠道流水号</th>
                      <th className="py-2 pr-3">尝试次数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((item) => (
                      <tr
                        key={item.id}
                        className="cursor-pointer border-t border-border hover:bg-muted/40"
                        onClick={() => void openDetail(item.id)}
                      >
                        <td className="py-2 pr-3">{item.executionNo}</td>
                        <td className="py-2 pr-3">
                          {item.orderNo}
                          {item.installmentSeq !== null ? ` · 第 ${item.installmentSeq} 批` : ''}
                        </td>
                        <td className="py-2 pr-3">{item.channelName}</td>
                        <td className="py-2 pr-3">
                          {formatPaymentExecutionAmount(item.amount, item.currency)}
                        </td>
                        <td className="py-2 pr-3">
                          <Badge tone={paymentExecutionStatusTone(item.status)}>
                            {paymentExecutionStatusLabel(item.status)}
                          </Badge>
                        </td>
                        <td className="py-2 pr-3 font-mono text-xs">
                          {item.channelSerialNo || '—'}
                        </td>
                        <td className="py-2 pr-3">{item.attemptCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="mt-4 flex flex-wrap gap-4 border-t border-border pt-3 text-sm">
                {page.summaryByCurrency.map((s) => (
                  <span key={s.currency} className="text-muted-foreground">
                    {s.currency}：{formatPaymentExecutionAmount(s.amount, s.currency)} · 共{' '}
                    {s.count} 笔（已付 {s.succeededCount} / 失败 {s.failedCount} / 待查证{' '}
                    {s.unknownCount}）
                  </span>
                ))}
              </div>

              <div className="mt-3 flex items-center gap-2 text-sm">
                <Button
                  variant="secondary"
                  disabled={currentPage <= 0}
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {currentPage + 1} / {totalPages} 页
                </span>
                <Button
                  variant="secondary"
                  disabled={currentPage + 1 >= totalPages}
                  onClick={() => setCurrentPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </>
          )
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title={`执行单详情 · ${detail.execution.executionNo}`}
          description={`付款单 ${detail.execution.orderNo} · 幂等键 ${detail.execution.idempotencyKey}`}
          extra={
            <div className="flex gap-2">
              <Button
                variant="secondary"
                disabled={!actions?.canVerify}
                onClick={() => void runVerify(detail.execution.id)}
              >
                状态查证
              </Button>
              <Button
                variant="secondary"
                disabled={!actions?.canRetry}
                onClick={() => void runRetry(detail.execution.id)}
              >
                查证后重发
              </Button>
            </div>
          }
        >
          {detail.execution.status === 'UNKNOWN' && (
            <Notice tone="warning" title="结果待查证：超时不等于失败">
              渠道没有给出确定结论。请先点「状态查证」—— 查证确认渠道侧确实没有成功，才允许重发；
              查证发现已成功时系统**不会**重发。重复付款是严重资损事故。
            </Notice>
          )}

          <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-3">
            <div>
              <dt className="text-muted-foreground">状态</dt>
              <dd>
                <Badge tone={paymentExecutionStatusTone(detail.execution.status)}>
                  {paymentExecutionStatusLabel(detail.execution.status)}
                </Badge>
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">金额</dt>
              <dd>
                {formatPaymentExecutionAmount(detail.execution.amount, detail.execution.currency)}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">渠道流水号</dt>
              <dd className="font-mono text-xs">{detail.execution.channelSerialNo || '—'}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">失败原因</dt>
              <dd>{detail.execution.failureReason || '—'}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">失败分类</dt>
              <dd>{paymentFailureClassLabel(detail.execution.failureClass)}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">锁汇</dt>
              <dd>
                {detail.execution.exchangeRate
                  ? formatPaymentExchangeRate(
                      detail.execution.exchangeRate,
                      detail.execution.currency,
                      detail.execution.baseCurrency,
                    )
                  : '单币种付款（不锁汇）'}
              </dd>
            </div>
          </dl>

          <h4 className="mt-5 text-sm font-medium">尝试记录</h4>
          {detail.attempts.length === 0 ? (
            <p className="text-sm text-muted-foreground">还没有任何尝试。</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3">类型</th>
                    <th className="py-2 pr-3">序号</th>
                    <th className="py-2 pr-3">结论</th>
                    <th className="py-2 pr-3">渠道流水号</th>
                    <th className="py-2 pr-3">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.attempts.map((a) => (
                    <tr key={a.id} className="border-t border-border">
                      <td className="py-2 pr-3">{paymentAttemptKindLabel(a.attemptKind)}</td>
                      <td className="py-2 pr-3">{a.attemptNo}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={paymentAttemptOutcomeTone(a.outcome)}>
                          {paymentAttemptOutcomeLabel(a.outcome)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">{a.channelSerialNo || '—'}</td>
                      <td className="py-2 pr-3">{a.failureReason || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h4 className="mt-5 text-sm font-medium">汇率锁定</h4>
          {detail.rateLocks.length === 0 ? (
            <p className="text-sm text-muted-foreground">单币种付款没有换算，因此没有锁汇。</p>
          ) : (
            <ul className="space-y-1 text-sm">
              {detail.rateLocks.map((lock) => (
                <li key={lock.id}>
                  {formatPaymentExchangeRate(lock.rate, lock.quoteCurrency, lock.baseCurrency)} ·{' '}
                  本位币金额 {formatPaymentExecutionAmount(lock.baseAmount, lock.baseCurrency)} ·{' '}
                  <Badge tone={lock.status === 'LOCKED' ? 'warning' : 'neutral'}>
                    {paymentRateLockStatusLabel(lock.status)}
                  </Badge>{' '}
                  <span className="text-muted-foreground">来源：{lock.sourceName || lock.sourceCode}</span>
                </li>
              ))}
            </ul>
          )}

          <h4 className="mt-5 text-sm font-medium">付款分摊</h4>
          {detail.allocations.length === 0 ? (
            <p className="text-sm text-muted-foreground">还没有付款分摊记录。</p>
          ) : (
            <ul className="space-y-1 text-sm">
              {detail.allocations.map((a) => (
                <li key={a.id}>
                  应付明细 {a.payableLineId} ← {formatPaymentExecutionAmount(a.amount, detail.execution.currency)}
                </li>
              ))}
            </ul>
          )}

          <h4 className="mt-5 text-sm font-medium">事件流</h4>
          <ul className="space-y-1 text-sm">
            {detail.events.map((e) => (
              <li key={e.id} className="text-muted-foreground">
                {e.occurredAt} · {paymentExecutionEventLabel(e.eventType)}
                {e.reason ? ` —— ${e.reason}` : ''}
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}

/** 候选项的唯一键（付款单 + 分批）。 */
function pendingKeyOf(order: FinancePaymentExecutionPendingOrder): string {
  return `${order.id}:${order.installmentId ?? 'full'}`;
}
