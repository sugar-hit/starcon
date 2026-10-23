'use client';

import { useCallback, useEffect, useState } from 'react';
import { Download, RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinancePayableCapabilities,
  FinancePayableDetail,
  FinancePayableOptions,
  FinancePayablePage,
  FinancePayablePolicyView,
  FinancePayableRow,
  FinancePayableUnpaidList,
  FinancePrepaymentDetail,
  FinancePrepaymentPage,
  FinancePrepaymentRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  allocatePrepayment,
  counterpartyTypeLabel,
  createPayable,
  createPrepayment,
  fetchPayable,
  fetchPayableCapabilities,
  fetchPayableOptions,
  fetchPayablePolicies,
  fetchPayableUnpaidList,
  fetchPayables,
  fetchPrepayment,
  fetchPrepayments,
  formatPayableAmount,
  formatPayableMoney,
  importPayablesFromAdSpend,
  importPayablesFromFirstMile,
  importPayablesFromPurchaseArrivals,
  obligationKindLabel,
  payableEventLabel,
  payableOverdueHint,
  payablePolicyStateLabel,
  payablePolicyStateTone,
  payableSourceLabel,
  payableSourceSystemLabel,
  payableStatusLabel,
  payableStatusTone,
  prepaymentStatusLabel,
  prepaymentStatusTone,
  updatePayablePolicy,
  voidPayable,
  voidPrepayment,
} from './payable-model';

/**
 * 12.1.2.1 应付单管理。
 *
 * <p>蓝图 12-finance.md：「管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、
 * 应付对象、金额与币种、到期日、已付金额与未付余额。」输出：「应付单 + 明细 + 未付清单。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「<b>应付、预付款与采购承诺分开建模</b>」⇒ 页面把**预付款放在自己的一段里**，
 *       不是应付单的 tab；采购承诺**没有入口**（本服务不建模，能力声明里写明归属 6 采购）。</li>
 *   <li>「<b>确认依据由业务合同和已批准政策决定，不能仅凭下单或收货统一推导</b>」⇒
 *       页面**第一块**是「应付确认政策」：每个来源 × 义务类型一个都不藏，
 *       没配过的显示「未配置」、配了但关掉的显示「已禁用」。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的四个东西</b>：
 *
 * <ol>
 *   <li><b>「手工建一张采购收货应付」的入口</b>：那三个上游来源**只能**由上游事实产生。</li>
 *   <li><b>「用供应商对账单 / 平台费用建应付」的入口</b>：上游（12.1.2.3 / 12.3.1.1）未交付，
 *       能力声明里如实报出归属模块。</li>
 *   <li><b>任何「标记已付」的按钮</b>：付款属 12.2.2.1（未交付），
 *       页面只显示已付金额与未付余额。</li>
 *   <li><b>跨币种的合计</b>：USD 与 EUR 加起来不是任何一个金额，汇总一律按币种分组渲染。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有应付单」。
 */
export function PayableWorkspace() {
  const [caps, setCaps] = useState<FinancePayableCapabilities | null>(null);
  const [options, setOptions] = useState<FinancePayableOptions | null>(null);
  const [page, setPage] = useState<FinancePayablePage | null>(null);
  const [unpaid, setUnpaid] = useState<FinancePayableUnpaidList | null>(null);
  const [policies, setPolicies] = useState<FinancePayablePolicyView[]>([]);
  const [detail, setDetail] = useState<FinancePayableDetail | null>(null);
  const [prepayments, setPrepayments] = useState<FinancePrepaymentPage | null>(null);
  const [prepaymentDetail, setPrepaymentDetail] = useState<FinancePrepaymentDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [source, setSource] = useState('');
  const [currency, setCurrency] = useState('');
  const [counterpartyRef, setCounterpartyRef] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 人工录入
  const [counterpartyName, setCounterpartyName] = useState('');
  const [counterpartyType, setCounterpartyType] = useState('SUPPLIER');
  const [entryCurrency, setEntryCurrency] = useState('USD');
  const [entryAmount, setEntryAmount] = useState('');
  const [entryDescription, setEntryDescription] = useState('');
  const [entryDueDate, setEntryDueDate] = useState('');

  // 预付款登记
  const [prepayName, setPrepayName] = useState('');
  const [prepayCurrency, setPrepayCurrency] = useState('USD');
  const [prepayAmount, setPrepayAmount] = useState('');
  const [prepayDate, setPrepayDate] = useState('');
  const [prepayReference, setPrepayReference] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(source ? { source } : {}),
    ...(currency ? { currency } : {}),
    ...(counterpartyRef ? { counterpartyRef } : {}),
    ...(overdueOnly ? { overdueOnly } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, u, pol, pp] = await Promise.all([
        fetchPayableCapabilities(),
        fetchPayableOptions(),
        fetchPayables(params),
        fetchPayableUnpaidList(),
        fetchPayablePolicies(),
        fetchPrepayments({ page: 0, size: 50 }),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setUnpaid(u);
      setPolicies(pol);
      setPrepayments(pp);
    } catch (e) {
      setError(e instanceof Error ? e.message : '应付单加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, source, currency, counterpartyRef, overdueOnly, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchPayable(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '应付单详情加载失败');
    }
  }

  async function openPrepayment(id: string) {
    setActionError('');
    try {
      setPrepaymentDetail(await fetchPrepayment(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '预付款详情加载失败');
    }
  }

  async function runImport(kind: 'arrivals' | 'first-mile' | 'ad-spend') {
    setActionError('');
    setNotice('');
    try {
      const result =
        kind === 'arrivals'
          ? await importPayablesFromPurchaseArrivals()
          : kind === 'first-mile'
            ? await importPayablesFromFirstMile()
            : await importPayablesFromAdSpend();
      const lines = [
        `读到 ${result.scanned} 条，新建 ${result.imported} 张，已存在跳过 ${result.skipped} 条`,
      ];
      if (result.truncated) {
        lines.push('⚠️ 上游翻页触到上限，本次只扫描了前一部分，未导入的应付需要再导一次');
      }
      for (const warning of result.warnings) lines.push(`⚠️ ${warning}`);
      setNotice(lines.join('；'));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '从上游导入应付失败');
    }
  }

  async function submitManual() {
    setActionError('');
    setNotice('');
    if (!counterpartyName.trim()) {
      setActionError('请写明应付对象名称');
      return;
    }
    const amount = Number(entryAmount);
    if (!entryAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('明细金额必须大于 0');
      return;
    }
    try {
      await createPayable({
        source: 'MANUAL',
        counterpartyType: counterpartyType as 'SUPPLIER' | 'CARRIER' | 'PLATFORM' | 'OTHER',
        counterpartyRef: null,
        counterpartyName: counterpartyName.trim(),
        currency: entryCurrency.trim().toUpperCase(),
        exchangeRate: null,
        baseCurrency: null,
        dueDate: entryDueDate ? entryDueDate : null,
        note: null,
        lines: [
          {
            description: entryDescription.trim() || '人工录入明细',
            skuCode: null,
            sourceReference: null,
            quantity: null,
            unitPrice: null,
            amount,
          },
        ],
      });
      setNotice('已登记一张应付单（建单与确认在同一个事务里完成）');
      setCounterpartyName('');
      setEntryAmount('');
      setEntryDescription('');
      setEntryDueDate('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记应付单失败');
    }
  }

  async function submitPrepayment() {
    setActionError('');
    setNotice('');
    if (!prepayName.trim()) {
      setActionError('请写明预付对象名称');
      return;
    }
    const amount = Number(prepayAmount);
    if (!prepayAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('预付金额必须大于 0');
      return;
    }
    if (!prepayDate) {
      setActionError('必须写明预计付款日');
      return;
    }
    try {
      await createPrepayment({
        counterpartyType: 'SUPPLIER',
        counterpartyRef: null,
        counterpartyName: prepayName.trim(),
        currency: prepayCurrency.trim().toUpperCase(),
        amount,
        expectedPaidDate: prepayDate,
        sourceReference: prepayReference.trim() || null,
        note: null,
      });
      setNotice('已登记一笔预付款（登记的是预付事实，付款本身属 12.2.2.1，未交付）');
      setPrepayName('');
      setPrepayAmount('');
      setPrepayDate('');
      setPrepayReference('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记预付款失败');
    }
  }

  async function savePolicy(
    sourceKey: string,
    obligationKind: string,
    enabled: boolean,
    dueDays: number,
  ) {
    setActionError('');
    setNotice('');
    try {
      await updatePayablePolicy(sourceKey, obligationKind, { enabled, dueDays, note: null });
      setNotice(
        `已更新「${payableSourceLabel(sourceKey)} · ${obligationKindLabel(obligationKind)}」的确认政策`,
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '更新应付确认政策失败');
    }
  }

  async function doVoid(row: FinancePayableRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('作废理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await voidPayable(row.id, reason.trim());
      setNotice(`已作废 ${row.payableNo} —— 作废是终态，整单冻结`);
      setDetail(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  async function doVoidPrepayment(row: FinancePrepaymentRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('作废理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await voidPrepayment(row.id, reason.trim());
      setNotice(`已作废预付款 ${row.prepaymentNo}`);
      setPrepaymentDetail(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废预付款失败');
    }
  }

  async function doAllocate(row: FinancePrepaymentRow) {
    setActionError('');
    setNotice('');
    const reference = window.prompt('抵扣到哪张采购单 / 合同号？', '');
    if (!reference || !reference.trim()) return;
    const amountText = window.prompt(
      `抵扣多少？（可抵扣余额 ${formatPayableMoney(row.unallocatedAmount, row.currency)}）`,
      String(row.unallocatedAmount),
    );
    const amount = Number(amountText);
    if (!amountText || Number.isNaN(amount) || amount <= 0) {
      setActionError('抵扣金额必须大于 0');
      return;
    }
    try {
      await allocatePrepayment(row.id, {
        targetType: 'PURCHASE_ORDER',
        payableId: null,
        targetReference: reference.trim(),
        amount,
        note: null,
      });
      setNotice(`已登记一笔抵扣（不改应付单的已付金额 —— 那是 12.2.2.1 的写入口）`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记预付款抵扣失败');
    }
  }

  const hasFilters = Boolean(status || source || currency || counterpartyRef || keyword) || overdueOnly;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="应付单管理"
        description="来源 / 应付对象 / 金额与币种 / 到期日 / 已付金额与未付余额"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" onClick={() => void runImport('arrivals')} disabled={loading}>
              <Download className="mr-2 h-4 w-4" />
              从到货应付确认导入
            </Button>
            <Button
              variant="secondary"
              onClick={() => void runImport('first-mile')}
              disabled={loading}
            >
              <Download className="mr-2 h-4 w-4" />
              从头程发运单导入
            </Button>
            <Button variant="secondary" onClick={() => void runImport('ad-spend')} disabled={loading}>
              <Download className="mr-2 h-4 w-4" />
              从广告花费导入
            </Button>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
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
          {/* ---------- 应付确认政策（红线所在，放最前） ---------- */}
          <SectionCard
            title="应付确认政策"
            description="什么业务确认为应付、账期多少天，由租户自己定。系统不替用户默认一个会计政策。"
          >
            <div className="space-y-3">
              {policies.map((policy) => (
                <PolicyRow
                  key={`${policy.source}-${policy.obligationKind}`}
                  policy={policy}
                  onSave={(enabled, dueDays) =>
                    void savePolicy(policy.source, policy.obligationKind, enabled, dueDays)
                  }
                />
              ))}
            </div>
          </SectionCard>

          {/* ---------- 汇总（按币种分开） ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册应付单" value={page?.total ?? 0} unit="单" />
            <StatCard label="未付清单条目" value={unpaid?.itemCount ?? 0} unit="条" />
            <StatCard label="逾期应付单" value={page?.overdueCount ?? 0} unit="单" />
            <StatCard
              label="未付清单基准日"
              value={unpaid?.asOfDate ?? '—'}
              comparisonLabel="按 UTC 日历日"
            />
          </div>

          {page && page.summaryByCurrency.length > 0 && (
            <SectionCard
              title="按币种的汇总"
              description="⚠️ 不同币种**不**合并：USD 与 EUR 加起来不是任何一个金额。"
            >
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">币种</th>
                    <th className="py-2">单头金额</th>
                    <th className="py-2">已付</th>
                    <th className="py-2">未付余额</th>
                    <th className="py-2">逾期未付</th>
                    <th className="py-2">张数</th>
                  </tr>
                </thead>
                <tbody>
                  {page.summaryByCurrency.map((s) => (
                    <tr key={s.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{s.currency}</td>
                      <td className="py-2">{formatPayableAmount(s.amount)}</td>
                      <td className="py-2">{formatPayableAmount(s.paidAmount)}</td>
                      <td className="py-2">{formatPayableAmount(s.unpaidAmount)}</td>
                      <td className="py-2">
                        {formatPayableAmount(s.overdueAmount)}
                        {s.overdueCount > 0 && (
                          <span className="text-muted-foreground"> （{s.overdueCount} 单）</span>
                        )}
                      </td>
                      <td className="py-2">{s.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </SectionCard>
          )}

          {/* ---------- 未付清单（12.1.2.1 的输出之一） ---------- */}
          {unpaid && (
            <SectionCard
              title="未付清单"
              description="只列未付清的应付；付款（12.2.2.1）未交付，已付金额当前恒为 0。"
            >
              {unpaid.items.length === 0 ? (
                <EmptyState
                  title="没有未付的应付单"
                  description="已付清与已作废的单不在清单里 —— 清单是「还要付什么」。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">单号</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">应付对象</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">未付余额</th>
                      <th className="py-2">到期日</th>
                      <th className="py-2">逾期天数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {unpaid.items.map((item) => (
                      <tr key={item.payableId} className="border-border border-t">
                        <td className="py-2">
                          <button
                            type="button"
                            className="underline underline-offset-4"
                            onClick={() => void openDetail(item.payableId)}
                          >
                            {item.payableNo}
                          </button>
                        </td>
                        <td className="py-2">{payableSourceLabel(item.source)}</td>
                        <td className="py-2">
                          {item.counterpartyName}
                          <span className="text-muted-foreground">
                            （{counterpartyTypeLabel(item.counterpartyType)}）
                          </span>
                        </td>
                        <td className="py-2">{item.currency}</td>
                        <td className="py-2">{formatPayableAmount(item.unpaidAmount)}</td>
                        <td className="py-2">{item.dueDate}</td>
                        <td className="py-2">
                          {item.daysOverdue > 0 ? (
                            <Badge tone="danger">{item.daysOverdue} 天</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 应付单列表 ---------- */}
          <SectionCard title="应付单" description="点一行看明细行与事件流。">
            <div className="mb-4 grid gap-3 md:grid-cols-4">
              <Input
                placeholder="关键字：单号 / 应付对象 / 上游单号"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {payableStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={source}
                onChange={(e) => {
                  setSource(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部来源</option>
                {(options?.sources ?? []).map((s) => (
                  <option key={s} value={s}>
                    {payableSourceLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合条件的应付单' : '还没有应付单'}
                description={
                  hasFilters
                    ? '换个筛选条件再试一次 —— 页面不做前端算术，筛选结果是后端算的。'
                    : '应付单有三个可产来源（采购收货 / 物流费用 / 广告费）与人工录入；上游导入需要先在上面的「应付确认政策」里启用对应来源。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">单号</th>
                    <th className="py-2">来源</th>
                    <th className="py-2">应付对象</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">已付 / 未付</th>
                    <th className="py-2">到期日</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-border border-t">
                      <td className="py-2">
                        <button
                          type="button"
                          className="underline underline-offset-4"
                          onClick={() => void openDetail(row.id)}
                        >
                          {row.payableNo}
                        </button>
                      </td>
                      <td className="py-2">
                        {payableSourceLabel(row.source)}
                        <span className="text-muted-foreground block text-xs">
                          {payableSourceSystemLabel(row.sourceSystem)}
                        </span>
                      </td>
                      <td className="py-2">
                        {row.counterpartyName}
                        <span className="text-muted-foreground block text-xs">
                          {counterpartyTypeLabel(row.counterpartyType)}
                        </span>
                      </td>
                      <td className="py-2">{formatPayableMoney(row.amount, row.currency)}</td>
                      <td className="py-2">
                        {formatPayableAmount(row.paidAmount)} / {formatPayableAmount(row.unpaidAmount)}
                      </td>
                      <td className="py-2">
                        {row.dueDate}
                        <span className="text-muted-foreground block text-xs">
                          {payableOverdueHint(row, unpaid?.asOfDate ?? row.dueDate)}
                        </span>
                      </td>
                      <td className="py-2">
                        <Badge tone={payableStatusTone(row.status)}>
                          {payableStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {row.status !== 'PAID' && row.status !== 'VOID' && (
                          <Button variant="ghost" onClick={() => void doVoid(row)}>
                            作废
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {page && page.total > page.items.length && (
              <div className="mt-4 flex items-center gap-2">
                <Button
                  variant="secondary"
                  disabled={currentPage === 0}
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-sm">
                  第 {page.page + 1} 页 · 共 {page.total} 单
                </span>
                <Button
                  variant="secondary"
                  disabled={(currentPage + 1) * page.size >= page.total}
                  onClick={() => setCurrentPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {/* ---------- 应付单详情 ---------- */}
          {detail && (
            <SectionCard title={`应付单 ${detail.payable.payableNo}`} description="明细行与事件流。">
              <div className="space-y-4">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">#</th>
                      <th className="py-2">说明</th>
                      <th className="py-2">数量</th>
                      <th className="py-2">单价</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">已付</th>
                      <th className="py-2">付款状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.lines.map((line) => (
                      <tr key={line.id} className="border-border border-t">
                        <td className="py-2">{line.lineNo}</td>
                        <td className="py-2">
                          {line.description}
                          {line.sourceReference && (
                            <span className="text-muted-foreground block text-xs">
                              上游单号 {line.sourceReference}
                            </span>
                          )}
                        </td>
                        <td className="py-2">{formatPayableAmount(line.quantity)}</td>
                        <td className="py-2">{formatPayableAmount(line.unitPrice)}</td>
                        <td className="py-2">{formatPayableAmount(line.amount)}</td>
                        <td className="py-2">{formatPayableAmount(line.paidAmount)}</td>
                        <td className="py-2">{line.paymentStatus}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div>
                  <h3 className="mb-2 text-sm font-medium">事件流</h3>
                  {detail.events.length === 0 ? (
                    <p className="text-muted-foreground text-sm">暂无事件。</p>
                  ) : (
                    <ul className="space-y-1 text-sm">
                      {detail.events.map((event) => (
                        <li key={event.id}>
                          <span className="text-muted-foreground">{event.occurredAt}</span> ·{' '}
                          {payableEventLabel(event.eventType)}
                          {event.reason && <span> · {event.reason}</span>}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            </SectionCard>
          )}

          {/* ---------- 人工录入 ---------- */}
          <SectionCard
            title="人工录入应付单"
            description="只有「人工录入」与「其他」能手工建单：采购收货 / 物流费用 / 广告费只能由上游事实产生。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <Input
                placeholder="应付对象名称"
                value={counterpartyName}
                onChange={(e) => setCounterpartyName(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={counterpartyType}
                onChange={(e) => setCounterpartyType(e.target.value)}
              >
                <option value="SUPPLIER">供应商</option>
                <option value="CARRIER">承运商</option>
                <option value="PLATFORM">平台</option>
                <option value="OTHER">其他</option>
              </select>
              <Input
                placeholder="币种（USD）"
                value={entryCurrency}
                onChange={(e) => setEntryCurrency(e.target.value)}
              />
              <Input
                placeholder="明细金额"
                value={entryAmount}
                onChange={(e) => setEntryAmount(e.target.value)}
              />
              <Input
                placeholder="明细说明（可选）"
                value={entryDescription}
                onChange={(e) => setEntryDescription(e.target.value)}
              />
              <Input
                placeholder="到期日（可选，默认按政策账期）"
                type="date"
                value={entryDueDate}
                onChange={(e) => setEntryDueDate(e.target.value)}
              />
            </div>
            <div className="mt-3">
              <Button onClick={() => void submitManual()}>登记应付单</Button>
            </div>
          </SectionCard>

          {/* ---------- 预付款（独立聚合，不是应付单的一部分） ---------- */}
          <SectionCard
            title="预付款"
            description="🔴 预付款与应付单**分开建模**：它没有「已付金额」字段，付款属 12.2.2.1（未交付）；抵扣也不改应付单的已付金额。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <Input
                placeholder="预付对象名称"
                value={prepayName}
                onChange={(e) => setPrepayName(e.target.value)}
              />
              <Input
                placeholder="币种（USD）"
                value={prepayCurrency}
                onChange={(e) => setPrepayCurrency(e.target.value)}
              />
              <Input
                placeholder="预付金额"
                value={prepayAmount}
                onChange={(e) => setPrepayAmount(e.target.value)}
              />
              <Input
                placeholder="预计付款日"
                type="date"
                value={prepayDate}
                onChange={(e) => setPrepayDate(e.target.value)}
              />
              <Input
                placeholder="关联采购单号 / 合同号（可选）"
                value={prepayReference}
                onChange={(e) => setPrepayReference(e.target.value)}
              />
            </div>
            <div className="mt-3">
              <Button variant="secondary" onClick={() => void submitPrepayment()}>
                登记预付款
              </Button>
            </div>

            {prepayments && prepayments.unallocatedByCurrency.length > 0 && (
              <p className="text-muted-foreground mt-3 text-sm">
                可抵扣余额（按币种分开）：
                {prepayments.unallocatedByCurrency
                  .map((t) => formatPayableMoney(t.unallocatedAmount, t.currency))
                  .join(' · ')}
              </p>
            )}

            <div className="mt-4">
              {!prepayments || prepayments.items.length === 0 ? (
                <EmptyState
                  title="还没有预付款"
                  description="预付款登记需要先在上面的「应付确认政策」里启用「人工录入 · 预付款」。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">单号</th>
                      <th className="py-2">预付对象</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">已抵扣 / 可抵扣</th>
                      <th className="py-2">预计付款日</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {prepayments.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2">
                          <button
                            type="button"
                            className="underline underline-offset-4"
                            onClick={() => void openPrepayment(row.id)}
                          >
                            {row.prepaymentNo}
                          </button>
                        </td>
                        <td className="py-2">
                          {row.counterpartyName}
                          {row.sourceReference && (
                            <span className="text-muted-foreground block text-xs">
                              {row.sourceReference}
                            </span>
                          )}
                        </td>
                        <td className="py-2">{formatPayableMoney(row.amount, row.currency)}</td>
                        <td className="py-2">
                          {formatPayableAmount(row.allocatedAmount)} /{' '}
                          {formatPayableAmount(row.unallocatedAmount)}
                        </td>
                        <td className="py-2">{row.expectedPaidDate}</td>
                        <td className="py-2">
                          <Badge tone={prepaymentStatusTone(row.status)}>
                            {prepaymentStatusLabel(row.status)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {row.status === 'OPEN' && (
                            <Button variant="ghost" onClick={() => void doAllocate(row)}>
                              登记抵扣
                            </Button>
                          )}
                          {row.status !== 'VOID' && row.status !== 'ALLOCATED' && (
                            <Button variant="ghost" onClick={() => void doVoidPrepayment(row)}>
                              作废
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            {prepaymentDetail && (
              <div className="mt-4">
                <h3 className="mb-2 text-sm font-medium">
                  预付款 {prepaymentDetail.prepayment.prepaymentNo} 的抵扣明细
                </h3>
                {prepaymentDetail.allocations.length === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有抵扣记录。</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {prepaymentDetail.allocations.map((a) => (
                      <li key={a.id}>
                        {a.allocatedAt} · {a.targetType} ·{' '}
                        {a.payableNo ?? a.targetReference ?? '—'} ·{' '}
                        {formatPayableMoney(a.amount, prepaymentDetail.prepayment.currency)}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------- 能力声明 ---------- */}
          {caps && (
            <SectionCard
              title="能力声明"
              description="哪些上游真接通、哪些还没交付、归谁 —— 页面不提供用未交付上游建单的入口。"
            >
              <div className="space-y-2">
                {caps.capabilities.map((cap) => (
                  <div key={`${cap.module}-${cap.name}`} className="text-sm">
                    <Badge tone={cap.available ? 'success' : 'warning'}>
                      {cap.available ? '可用' : '不可用'}
                    </Badge>{' '}
                    <span className="font-medium">{cap.name}</span>{' '}
                    <span className="text-muted-foreground">（{cap.module}）</span>
                    <p className="text-muted-foreground mt-1">{cap.detail}</p>
                    {!cap.available && cap.ownerModule && (
                      <p className="text-muted-foreground text-xs">
                        归属模块：{cap.ownerModule}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

/**
 * 一条确认政策。
 *
 * <p>🔴 「未配置」与「已禁用」用不同文案；采购承诺（COMMITMENT）**不给开关** ——
 * 本服务不建模它，给一个能点的开关就是骗人。
 */
function PolicyRow({
  policy,
  onSave,
}: {
  policy: FinancePayablePolicyView;
  onSave: (enabled: boolean, dueDays: number) => void;
}) {
  const [dueDays, setDueDays] = useState(String(policy.dueDays));
  const [enabled, setEnabled] = useState(policy.enabled);

  if (policy.obligationKind === 'COMMITMENT') {
    return (
      <div className="border-border rounded-md border p-3 text-sm">
        <Badge tone={payablePolicyStateTone(policy)}>{payablePolicyStateLabel(policy)}</Badge>{' '}
        <span className="font-medium">采购承诺</span>{' '}
        <span className="text-muted-foreground">（归属 6 采购）</span>
        <p className="text-muted-foreground mt-1">{policy.detail}</p>
      </div>
    );
  }

  return (
    <div className="border-border rounded-md border p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={payablePolicyStateTone(policy)}>{payablePolicyStateLabel(policy)}</Badge>{' '}
        <span className="font-medium">{policy.sourceLabel}</span>{' '}
        <span className="text-muted-foreground">· {policy.obligationKindLabel}</span>
        {!policy.producible && (
          <Badge tone="warning">上游未交付（{policy.ownerModule}）</Badge>
        )}
        <span className="ml-auto flex items-center gap-2">
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
              aria-label={`启用 ${policy.sourceLabel}`}
            />
            启用
          </label>
          <Input
            className="h-8 w-24"
            value={dueDays}
            onChange={(e) => setDueDays(e.target.value)}
            aria-label="账期天数"
          />
          <span className="text-muted-foreground">天账期</span>
          <Button
            variant="secondary"
            onClick={() => onSave(enabled, Number(dueDays) || 0)}
            disabled={!policy.producible}
          >
            保存
          </Button>
        </span>
      </div>
      <p className="text-muted-foreground mt-1">{policy.detail}</p>
    </div>
  );
}
