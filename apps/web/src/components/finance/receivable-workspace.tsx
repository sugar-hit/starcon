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
  FinanceCollectionTaskRow,
  FinanceReceivableAging,
  FinanceReceivableCapabilities,
  FinanceReceivableDetail,
  FinanceReceivableOptions,
  FinanceReceivablePage,
  FinanceReceivablePolicyView,
  FinanceReceivableRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  agingBucketLabel,
  closeCollectionTask,
  collectionTaskLevelLabel,
  collectionTaskStatusLabel,
  counterpartyTypeLabel,
  createCollectionTask,
  createReceivable,
  fetchCollectionTasks,
  fetchReceivable,
  fetchReceivableAging,
  fetchReceivableCapabilities,
  fetchReceivableOptions,
  fetchReceivablePolicies,
  fetchReceivables,
  flagReceivableDispute,
  formatReceivableAmount,
  formatReceivableMoney,
  generateOverdueCollectionTasks,
  importReceivablesFromShipmentConfirmations,
  receivableEventLabel,
  receivableOverdueHint,
  receivablePolicyStateLabel,
  receivablePolicyStateTone,
  receivableSourceLabel,
  receivableSourceSystemLabel,
  receivableStatusLabel,
  receivableStatusTone,
  updateReceivablePolicy,
  voidReceivable,
} from './receivable-model';

/**
 * 12.1.1.1 应收单管理。
 *
 * <p>蓝图 12-finance.md：「管理应收账款：来源（平台结算、平台代扣、B2B 客户账期、其他）、
 * 应收对象、金额与币种、到期日、账龄、明细核销状态。」输出：「应收单 + 明细行 + 账龄分布；
 * 生成催收任务。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：「<b>未发货订单不默认等于收入</b>——应收确认政策需单独配置」。
 * 所以页面**第一块**就是「应收确认政策」：五个来源一个都不藏，
 * 没配过的显示「未配置」、配了但关掉的显示「已禁用」——
 * 把它们合成一个「否」会让「你还没决定」看起来像「你决定了这个来源不产生应收」。
 *
 * <p><b>页面刻意没有的四个东西</b>：
 *
 * <ol>
 *   <li><b>「用平台结算建应收」的入口</b>：上游 12.3.1 结算**未交付**，
 *       放一个能点的入口就是骗人 —— 能力声明里如实报出归属模块 12.3.1.1。</li>
 *   <li><b>「手工建一张 B2B 账期应收」</b>：B2B 账期应收**只能**由发货确认产生，
 *       手工录入等于自己编一个发货确认事实。</li>
 *   <li><b>「核销」与「坏账核销」按钮</b>：12.2.1.2 / 12.1.1.3 **均未交付**，
 *       页面只显示明细的核销状态与已核销金额。</li>
 *   <li><b>跨币种的合计</b>：USD 与 EUR 加起来不是任何一个金额，
 *       汇总一律按币种分组渲染。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有应收单」。
 */
export function ReceivableWorkspace() {
  const [caps, setCaps] = useState<FinanceReceivableCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceReceivableOptions | null>(null);
  const [page, setPage] = useState<FinanceReceivablePage | null>(null);
  const [aging, setAging] = useState<FinanceReceivableAging | null>(null);
  const [policies, setPolicies] = useState<FinanceReceivablePolicyView[]>([]);
  const [tasks, setTasks] = useState<FinanceCollectionTaskRow[]>([]);
  const [detail, setDetail] = useState<FinanceReceivableDetail | null>(null);

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
  const [disputeOnly, setDisputeOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 人工录入
  const [counterpartyName, setCounterpartyName] = useState('');
  const [counterpartyType, setCounterpartyType] = useState('CUSTOMER');
  const [entryCurrency, setEntryCurrency] = useState('USD');
  const [entryAmount, setEntryAmount] = useState('');
  const [entryDescription, setEntryDescription] = useState('');
  const [entryDueDate, setEntryDueDate] = useState('');

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
    ...(disputeOnly ? { disputeOnly } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, g, pol, t] = await Promise.all([
        fetchReceivableCapabilities(),
        fetchReceivableOptions(),
        fetchReceivables(params),
        fetchReceivableAging(),
        fetchReceivablePolicies(),
        fetchCollectionTasks({ page: 0, size: 50 }),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setAging(g);
      setPolicies(pol);
      setTasks(t.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '应收单加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, source, currency, counterpartyRef, overdueOnly, disputeOnly, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchReceivable(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '应收单详情加载失败');
    }
  }

  async function runImport() {
    setActionError('');
    setNotice('');
    try {
      const result = await importReceivablesFromShipmentConfirmations();
      const lines = [
        `读到 ${result.scanned} 条，新建 ${result.imported} 张，已存在跳过 ${result.skipped} 条`,
      ];
      if (result.truncated) {
        lines.push('⚠️ 上游翻页触到上限，本次只扫描了前一部分，未导入的应收需要再导一次');
      }
      for (const warning of result.warnings) lines.push(`⚠️ ${warning}`);
      setNotice(lines.join('；'));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '从发货确认导入失败');
    }
  }

  async function runGenerateOverdue() {
    setActionError('');
    setNotice('');
    try {
      const created = await generateOverdueCollectionTasks();
      setNotice(
        created.length ? `新建了 ${created.length} 条逾期催收任务` : '没有需要新建的逾期催收任务',
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成逾期催收任务失败');
    }
  }

  async function submitManual() {
    setActionError('');
    setNotice('');
    if (!counterpartyName.trim()) {
      setActionError('请写明应收对象名称');
      return;
    }
    const amount = Number(entryAmount);
    if (!entryAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('明细金额必须大于 0');
      return;
    }
    try {
      await createReceivable({
        source: 'MANUAL',
        counterpartyType: counterpartyType as 'PLATFORM' | 'CUSTOMER' | 'OTHER',
        counterpartyRef: null,
        counterpartyName: counterpartyName.trim(),
        shopId: null,
        shopName: null,
        currency: entryCurrency.trim().toUpperCase(),
        exchangeRate: null,
        baseCurrency: null,
        dueDate: entryDueDate ? entryDueDate : null,
        note: null,
        lines: [
          {
            description: entryDescription.trim() || '人工录入明细',
            skuCode: null,
            orderNo: null,
            sourceReference: null,
            quantity: null,
            unitPrice: null,
            amount,
          },
        ],
      });
      setNotice('已登记一张应收单（建单与确认在同一个事务里完成）');
      setCounterpartyName('');
      setEntryAmount('');
      setEntryDescription('');
      setEntryDueDate('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记应收单失败');
    }
  }

  async function savePolicy(sourceKey: string, enabled: boolean, dueDays: number) {
    setActionError('');
    setNotice('');
    try {
      await updateReceivablePolicy(sourceKey, { enabled, dueDays, note: null });
      setNotice(`已更新「${receivableSourceLabel(sourceKey)}」的应收确认政策`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '更新应收确认政策失败');
    }
  }

  async function doVoid(row: FinanceReceivableRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('作废理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await voidReceivable(row.id, reason.trim());
      setNotice(`已作废 ${row.receivableNo} —— 作废是终态，整单冻结`);
      setDetail(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  async function doDispute(row: FinanceReceivableRow, flagged: boolean) {
    setActionError('');
    setNotice('');
    const note = flagged ? window.prompt('争议情况（必填）：', '') : '';
    if (flagged && (!note || !note.trim())) return;
    try {
      await flagReceivableDispute(row.id, { flagged, note: note ? note.trim() : null });
      setNotice(flagged ? '已标注「争议中」' : '已取消「争议中」标注');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '更新争议标注失败');
    }
  }

  async function addTask(row: FinanceReceivableRow) {
    setActionError('');
    setNotice('');
    try {
      await createCollectionTask(row.id, {
        level: 'OVERDUE',
        dueDate: null,
        assigneeName: null,
        reason: '页面发起',
      });
      setNotice(`已为 ${row.receivableNo} 生成逾期催收任务`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成催收任务失败');
    }
  }

  async function closeTask(task: FinanceCollectionTaskRow) {
    setActionError('');
    setNotice('');
    try {
      await closeCollectionTask(task.id, '页面关闭');
      setNotice('催收任务已关闭');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '关闭催收任务失败');
    }
  }

  const hasFilters =
    Boolean(status || source || currency || counterpartyRef || keyword) ||
    overdueOnly ||
    disputeOnly;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="应收单管理"
        description="来源 / 应收对象 / 金额与币种 / 到期日 / 账龄 / 明细核销状态"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => void runImport()} disabled={loading}>
              <Download className="mr-2 h-4 w-4" />
              从发货确认导入
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
          {/* ---------- 应收确认政策（红线所在，放最前） ---------- */}
          <SectionCard
            title="应收确认政策"
            description="什么业务确认为应收、账期多少天，由租户自己定。系统不替用户默认一个会计政策。"
          >
            <div className="space-y-3">
              {policies.map((policy) => (
                <PolicyRow
                  key={policy.source}
                  policy={policy}
                  onSave={(enabled, dueDays) => void savePolicy(policy.source, enabled, dueDays)}
                />
              ))}
            </div>
          </SectionCard>

          {/* ---------- 汇总（按币种分开） ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册应收单" value={page?.total ?? 0} unit="单" />
            <StatCard label="未核销应收单" value={aging?.openCount ?? 0} unit="单" />
            <StatCard label="逾期应收单" value={page?.overdueCount ?? 0} unit="单" />
            <StatCard
              label="账龄基准日"
              value={aging?.asOfDate ?? '—'}
              comparisonLabel="按 UTC 日历日分桶"
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
                    <th className="py-2">已核销</th>
                    <th className="py-2">未核销</th>
                    <th className="py-2">逾期未核销</th>
                    <th className="py-2">争议中</th>
                    <th className="py-2">张数</th>
                  </tr>
                </thead>
                <tbody>
                  {page.summaryByCurrency.map((s) => (
                    <tr key={s.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{s.currency}</td>
                      <td className="py-2">{formatReceivableAmount(s.amount)}</td>
                      <td className="py-2">{formatReceivableAmount(s.settledAmount)}</td>
                      <td className="py-2">{formatReceivableAmount(s.openAmount)}</td>
                      <td className="py-2">
                        {formatReceivableAmount(s.overdueAmount)}
                        {s.overdueCount > 0 && (
                          <span className="text-muted-foreground"> （{s.overdueCount} 单）</span>
                        )}
                      </td>
                      <td className="py-2">{formatReceivableAmount(s.disputeAmount)}</td>
                      <td className="py-2">{s.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </SectionCard>
          )}

          {/* ---------- 账龄分布 ---------- */}
          {aging && (
            <SectionCard
              title="账龄分布"
              description="五档 × 币种；争议中金额单列 —— 跨境应收最大的风险是平台争议冻结。"
            >
              {aging.buckets.length === 0 ? (
                <EmptyState
                  title="没有未核销的应收单"
                  description="账龄分布只统计未核销与部分核销的应收。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">账龄区间</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">未核销金额</th>
                      <th className="py-2">其中争议中</th>
                      <th className="py-2">张数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {aging.buckets.map((b) => (
                      <tr key={`${b.bucket}-${b.currency}`} className="border-border border-t">
                        <td className="py-2">{agingBucketLabel(b.bucket)}</td>
                        <td className="py-2">{b.currency}</td>
                        <td className="py-2">{formatReceivableAmount(b.amount)}</td>
                        <td className="py-2">
                          {b.disputeAmount > 0 ? (
                            <Badge tone="warning">{formatReceivableAmount(b.disputeAmount)}</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="py-2">{b.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}

          {/* ---------- 应收单列表 ---------- */}
          <SectionCard title="应收单" description="点一行看明细行、事件流与催收任务。">
            <div className="mb-4 grid gap-3 md:grid-cols-4">
              <Input
                placeholder="关键字：单号 / 应收对象 / 上游单号"
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
                    {receivableStatusLabel(s)}
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
                    {receivableSourceLabel(s)}
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
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={counterpartyRef}
                onChange={(e) => {
                  setCounterpartyRef(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部应收对象</option>
                {(options?.counterparties ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={overdueOnly}
                  onChange={(e) => {
                    setOverdueOnly(e.target.checked);
                    setCurrentPage(0);
                  }}
                />
                只看逾期
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={disputeOnly}
                  onChange={(e) => {
                    setDisputeOnly(e.target.checked);
                    setCurrentPage(0);
                  }}
                />
                只看争议中
              </label>
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合筛选条件的应收单' : '还没有应收单'}
                description={
                  hasFilters
                    ? '把筛选条件放宽一点再试。'
                    : '先在上面的「应收确认政策」里启用一个来源（否则任何来源都产生不了应收），再从「从发货确认导入」或下面的人工录入建单。'
                }
              />
            ) : (
              <>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">单号</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">应收对象</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">已核销</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">到期日</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2">
                          <button
                            type="button"
                            className="underline-offset-2 hover:underline"
                            onClick={() => void openDetail(row.id)}
                          >
                            {row.receivableNo}
                          </button>
                          {row.disputeFlag && (
                            <Badge tone="warning" className="ml-2">
                              争议中
                            </Badge>
                          )}
                          {row.sourceReference && (
                            <div className="text-muted-foreground text-copy-12">
                              上游 {row.sourceReference}
                            </div>
                          )}
                        </td>
                        <td className="py-2">
                          {receivableSourceLabel(row.source)}
                          <div className="text-muted-foreground text-copy-12">
                            {receivableSourceSystemLabel(row.sourceSystem)}
                          </div>
                        </td>
                        <td className="py-2">
                          {row.counterpartyName}
                          <div className="text-muted-foreground text-copy-12">
                            {counterpartyTypeLabel(row.counterpartyType)}
                          </div>
                        </td>
                        <td className="py-2">{formatReceivableMoney(row.amount, row.currency)}</td>
                        <td className="py-2">{formatReceivableAmount(row.settledAmount)}</td>
                        <td className="py-2">
                          <Badge tone={receivableStatusTone(row.status)}>
                            {receivableStatusLabel(row.status)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {row.dueDate}
                          <div className="text-muted-foreground text-copy-12">
                            {receivableOverdueHint(row, aging?.asOfDate ?? '')}
                          </div>
                        </td>
                        <td className="py-2">
                          <div className="flex flex-wrap gap-2">
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void openDetail(row.id)}
                            >
                              详情
                            </Button>
                            {row.status !== 'SETTLED' && row.status !== 'VOID' && (
                              <>
                                <Button size="sm" variant="ghost" onClick={() => void addTask(row)}>
                                  建催收任务
                                </Button>
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => void doDispute(row, !row.disputeFlag)}
                                >
                                  {row.disputeFlag ? '取消争议' : '标注争议'}
                                </Button>
                                <Button size="sm" variant="ghost" onClick={() => void doVoid(row)}>
                                  作废
                                </Button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="text-muted-foreground mt-3 flex items-center justify-between text-sm">
                  <span>
                    共 {page.total} 单，第 {page.page + 1} 页
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={page.page <= 0}
                      onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={(page.page + 1) * page.size >= page.total}
                      onClick={() => setCurrentPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---------- 详情 ---------- */}
          {detail && (
            <SectionCard
              title={`应收单详情 · ${detail.receivable.receivableNo}`}
              description={`${receivableSourceLabel(detail.receivable.source)} · ${detail.receivable.counterpartyName}`}
              extra={
                <Button size="sm" variant="secondary" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              }
            >
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-1 text-sm">
                  <p>
                    单头金额：
                    {formatReceivableMoney(detail.receivable.amount, detail.receivable.currency)}
                  </p>
                  <p>已核销：{formatReceivableAmount(detail.receivable.settledAmount)}</p>
                  <p>
                    本位币金额：
                    {detail.receivable.baseAmount === null
                      ? '未折算（这张单没有汇率快照）'
                      : formatReceivableMoney(
                          detail.receivable.baseAmount,
                          detail.receivable.baseCurrency,
                        )}
                  </p>
                  <p>
                    汇率快照：
                    {detail.receivable.exchangeRate === null
                      ? '未登记'
                      : `${detail.receivable.exchangeRate}（${detail.receivable.baseCurrency ?? ''}）`}
                  </p>
                  <p>
                    状态：
                    <Badge tone={receivableStatusTone(detail.receivable.status)}>
                      {receivableStatusLabel(detail.receivable.status)}
                    </Badge>
                  </p>
                  <p>确认时刻：{detail.receivable.confirmedAt}</p>
                  <p>到期日：{detail.receivable.dueDate}</p>
                  {detail.receivable.disputeFlag && (
                    <p>
                      争议：<Badge tone="warning">争议中</Badge> {detail.receivable.disputeNote}
                    </p>
                  )}
                </div>
                <div>
                  <h4 className="text-label-13 mb-2 font-semibold">明细行</h4>
                  {detail.lines.length === 0 ? (
                    <EmptyState title="没有明细行" />
                  ) : (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-1">#</th>
                          <th className="py-1">说明</th>
                          <th className="py-1">订单号</th>
                          <th className="py-1">金额</th>
                          <th className="py-1">已核销</th>
                          <th className="py-1">核销状态</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.lines.map((line) => (
                          <tr key={line.id} className="border-border border-t">
                            <td className="py-1">{line.lineNo}</td>
                            <td className="py-1">{line.description}</td>
                            <td className="py-1">{line.orderNo ?? '—'}</td>
                            <td className="py-1">{formatReceivableAmount(line.amount)}</td>
                            <td className="py-1">{formatReceivableAmount(line.settledAmount)}</td>
                            <td className="py-1">
                              <Badge tone="neutral">{line.settlementStatus}</Badge>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>

              <h4 className="text-label-13 mb-2 mt-5 font-semibold">事件流</h4>
              {detail.events.length === 0 ? (
                <EmptyState title="没有事件" />
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.events.map((e) => (
                    <li key={e.id}>
                      <span className="text-muted-foreground">{e.occurredAt}</span> ·{' '}
                      {receivableEventLabel(e.eventType)}
                      {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus
                        ? `（${e.fromStatus} → ${e.toStatus}）`
                        : ''}
                      {e.reason ? ` · ${e.reason}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 催收任务 ---------- */}
          <SectionCard
            title="催收任务"
            description="同一张应收同一级别只允许一条任务（重复催收是同一次催收）。"
            extra={
              <Button size="sm" variant="secondary" onClick={() => void runGenerateOverdue()}>
                批量生成逾期任务
              </Button>
            }
          >
            {tasks.length === 0 ? (
              <EmptyState
                title="还没有催收任务"
                description="逾期应收可以批量生成逾期催收任务，或在列表里给单张应收建任务。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">任务号</th>
                    <th className="py-2">应收单</th>
                    <th className="py-2">级别</th>
                    <th className="py-2">到期日</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.map((task) => (
                    <tr key={task.id} className="border-border border-t">
                      <td className="py-2">{task.taskNo}</td>
                      <td className="py-2">{task.receivableNo}</td>
                      <td className="py-2">{collectionTaskLevelLabel(task.level)}</td>
                      <td className="py-2">{task.dueDate}</td>
                      <td className="py-2">
                        <Badge tone={task.status === 'OPEN' ? 'warning' : 'success'}>
                          {collectionTaskStatusLabel(task.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {task.status === 'OPEN' && (
                          <Button size="sm" variant="ghost" onClick={() => void closeTask(task)}>
                            关闭
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 人工录入 ---------- */}
          <SectionCard
            title="人工录入应收单"
            description="⚠️ 只有「人工录入」与「其他」能手工建单；B2B 客户账期应收只能由发货确认产生。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <Input
                placeholder="应收对象名称"
                value={counterpartyName}
                onChange={(e) => setCounterpartyName(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={counterpartyType}
                onChange={(e) => setCounterpartyType(e.target.value)}
              >
                <option value="CUSTOMER">客户</option>
                <option value="PLATFORM">平台</option>
                <option value="OTHER">其他</option>
              </select>
              <Input
                placeholder="币种（USD / EUR / CNY …）"
                value={entryCurrency}
                onChange={(e) => setEntryCurrency(e.target.value)}
              />
              <Input
                placeholder="明细说明"
                value={entryDescription}
                onChange={(e) => setEntryDescription(e.target.value)}
              />
              <Input
                placeholder="明细金额"
                value={entryAmount}
                onChange={(e) => setEntryAmount(e.target.value)}
              />
              <Input
                type="date"
                value={entryDueDate}
                onChange={(e) => setEntryDueDate(e.target.value)}
              />
            </div>
            <div className="mt-3">
              <Button onClick={() => void submitManual()}>登记应收单</Button>
            </div>
          </SectionCard>

          {/* ---------- 能力边界 ---------- */}
          {caps && (
            <SectionCard title="能力边界" description="哪些上游真接通、哪些还没交付、归谁。">
              <ul className="space-y-2 text-sm">
                {caps.capabilities.map((c) => (
                  <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                    <Badge tone={c.available ? 'success' : 'warning'}>
                      {c.available ? '可用' : '未接入'}
                    </Badge>
                    <span>
                      <span className="font-medium">
                        {c.module} {c.name}
                      </span>
                      {c.ownerModule && (
                        <span className="text-muted-foreground"> （归属 {c.ownerModule}）</span>
                      )}
                      <div className="text-muted-foreground">{c.detail}</div>
                    </span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

/** 一行应收确认政策：可启用 / 禁用，可改账期天数。 */
function PolicyRow({
  policy,
  onSave,
}: {
  policy: FinanceReceivablePolicyView;
  onSave: (enabled: boolean, dueDays: number) => void;
}) {
  const [dueDays, setDueDays] = useState(String(policy.dueDays));
  return (
    <div className="border-border flex flex-wrap items-center gap-3 border-b pb-3 last:border-b-0">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-medium">{policy.sourceLabel}</span>
          <Badge tone={receivablePolicyStateTone(policy)}>
            {receivablePolicyStateLabel(policy)}
          </Badge>
          {!policy.producible && (
            <Badge tone="warning">当前不可产生（上游 {policy.ownerModule || '未交付'}）</Badge>
          )}
        </div>
        <p className="text-muted-foreground text-copy-12">{policy.detail}</p>
      </div>
      <Input
        className="w-28"
        value={dueDays}
        onChange={(e) => setDueDays(e.target.value)}
        placeholder="账期天数"
      />
      <Button size="sm" variant="secondary" onClick={() => onSave(true, Number(dueDays) || 0)}>
        启用
      </Button>
      <Button size="sm" variant="ghost" onClick={() => onSave(false, Number(dueDays) || 0)}>
        禁用
      </Button>
    </div>
  );
}
