'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  Badge,
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
  FinanceReconCapabilities,
  FinanceReconDifferencePage,
  FinanceReconEventPage,
  FinanceReconMatchPage,
  FinanceReconOptions,
  FinanceReconTaskDetail,
  FinanceReconTaskPage,
  FinanceReconTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  createReconciliationTask,
  fetchReconciliationCapabilities,
  fetchReconciliationDifferences,
  fetchReconciliationEvents,
  fetchReconciliationMatches,
  fetchReconciliationOptions,
  fetchReconciliationTask,
  fetchReconciliationTasks,
  formatReconciliationDifference,
  formatReconciliationMoney,
  reconciliationBookSourceLabel,
  reconciliationDifferenceActionHint,
  reconciliationDifferenceStatusLabel,
  reconciliationDifferenceTone,
  reconciliationDifferenceTypeLabel,
  reconciliationDirectionLabel,
  reconciliationEventLabel,
  reconciliationExternalKindLabel,
  reconciliationMatchBlockReason,
  reconciliationMatchHint,
  reconciliationScopeFrozenHint,
  reconciliationScopeLabel,
  reconciliationShapeLabel,
  reconciliationShapeTone,
  reconciliationSideLabel,
  reconciliationStatusLabel,
  reconciliationStatusTone,
  runReconciliationMatch,
  updateReconciliationTask,
} from './reconciliation-task-model';

/**
 * 12.3.2.1 对账任务。
 *
 * <p>蓝图 12-finance.md：「发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、
 * 运行自动匹配、输出匹配结果与差异清单。」输入：「对账范围；我方账数据（结算、应收、应付、
 * 银行流水）；平台账单。」输出：「匹配结果（一对一、一对多、多对一）+ 差异清单。」
 * 加粗句：「<b>不允许『金额相同即匹配』</b>——金额相同只是候选，必须结合订单号、日期、类型。」
 *
 * <p><b>页面围绕三块内容组织</b>：
 *
 * <ol>
 *   <li><b>对账任务</b>：列表（真分页 + 真筛选 + 真搜索）+ 建任务 / 改范围
 *       （店铺 + 周期 + 账户 + 我方账来源）；</li>
 *   <li><b>匹配结果</b>：按形态分组的匹配清单，**每行都写着「比对了哪几个维度」** ——
 *       这一栏是「不允许金额相同即匹配」在界面上的落点；</li>
 *   <li><b>差异清单</b>：平台账单多出 / 我方账多出 / 金额对不齐 / 币种不一致 / 维度对不上，
 *       **按币种分开呈现**，差额算不出来就写「汇率不可得」而**不是 0**。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：平台侧合计、我方侧合计、差额、匹配数全由服务端算好，
 * 页面只原样展示；「算不出」（服务端给 null）显示「—」而**不是 0**。
 *
 * <p>🔴 <b>不允许「金额相同即匹配」</b>：匹配由**服务端**按「至少两个独立维度」跑，
 * 页面**不**自己配对，也**不**提供「手动把这两行连起来」的按钮。
 *
 * <p>🔴 <b>已执行的任务不可改写</b>：MATCHED 态下「改范围」与「跑匹配」都禁用，
 * 并把「要重来请新建一个任务」写在页面上。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「还没建任务」与「筛选无结果」。
 */

/** 我方账来源（受控词表，与服务端一致；下拉里的选项另由 /options 给真实值）。 */
const BOOK_SOURCES = ['RECEIVABLE', 'PAYMENT_ORDER', 'BANK_STATEMENT'] as const;

/** 差异类型筛选（受控词表）。 */
const DIFFERENCE_TYPES = [
  'EXTERNAL_ONLY',
  'BOOK_ONLY',
  'AMOUNT_MISMATCH',
  'CURRENCY_MISMATCH',
  'DIMENSION_MISMATCH',
] as const;

/** 匹配形态筛选（受控词表）。 */
const SHAPES = ['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'] as const;

const PAGE_SIZE = 20;

export function ReconciliationTaskWorkspace() {
  const [capabilities, setCapabilities] = useState<FinanceReconCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceReconOptions | null>(null);
  const [tasks, setTasks] = useState<FinanceReconTaskPage | null>(null);
  const [selected, setSelected] = useState<FinanceReconTaskRow | null>(null);
  const [detail, setDetail] = useState<FinanceReconTaskDetail | null>(null);
  const [matches, setMatches] = useState<FinanceReconMatchPage | null>(null);
  const [differences, setDifferences] = useState<FinanceReconDifferencePage | null>(null);
  const [events, setEvents] = useState<FinanceReconEventPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 列表筛选
  const [accountFilter, setAccountFilter] = useState('');
  const [shopFilter, setShopFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);

  // 明细筛选
  const [shapeFilter, setShapeFilter] = useState('');
  const [bookSourceFilter, setBookSourceFilter] = useState('');
  const [differenceTypeFilter, setDifferenceTypeFilter] = useState('');
  const [currencyFilter, setCurrencyFilter] = useState('');
  const [sideFilter, setSideFilter] = useState('');
  const [matchPage, setMatchPage] = useState(0);
  const [differencePage, setDifferencePage] = useState(0);

  // 建 / 改任务草稿
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftAccountId, setDraftAccountId] = useState('');
  const [draftShopId, setDraftShopId] = useState('');
  const [draftPeriodStart, setDraftPeriodStart] = useState('');
  const [draftPeriodEnd, setDraftPeriodEnd] = useState('');
  const [draftSources, setDraftSources] = useState<string[]>([]);
  const [draftWindowDays, setDraftWindowDays] = useState('7');
  const [draftTolerance, setDraftTolerance] = useState('0');
  const [draftNote, setDraftNote] = useState('');

  const params = useMemo(
    () => ({
      ...(accountFilter ? { accountId: accountFilter } : {}),
      ...(shopFilter ? { shopId: shopFilter } : {}),
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [accountFilter, shopFilter, statusFilter, keyword],
  );

  const loadTasks = useCallback(async () => {
    const page = await fetchReconciliationTasks({ ...params, page: pageNo, size: PAGE_SIZE });
    setTasks(page);
  }, [params, pageNo]);

  const loadDetail = useCallback(
    async (taskId: string) => {
      setDetailLoading(true);
      try {
        const [detailResult, matchResult, differenceResult, eventResult] = await Promise.all([
          fetchReconciliationTask(taskId),
          fetchReconciliationMatches(taskId, {
            ...(shapeFilter ? { shape: shapeFilter } : {}),
            ...(bookSourceFilter ? { bookSource: bookSourceFilter } : {}),
            page: matchPage,
            size: PAGE_SIZE,
          }),
          fetchReconciliationDifferences(taskId, {
            ...(differenceTypeFilter ? { differenceType: differenceTypeFilter } : {}),
            ...(currencyFilter ? { currency: currencyFilter } : {}),
            ...(sideFilter ? { side: sideFilter } : {}),
            page: differencePage,
            size: PAGE_SIZE,
          }),
          fetchReconciliationEvents(taskId, { page: 0, size: PAGE_SIZE }),
        ]);
        setDetail(detailResult);
        setMatches(matchResult);
        setDifferences(differenceResult);
        setEvents(eventResult);
      } finally {
        setDetailLoading(false);
      }
    },
    [shapeFilter, bookSourceFilter, matchPage, differenceTypeFilter, currencyFilter, sideFilter, differencePage],
  );

  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, opts] = await Promise.all([
          fetchReconciliationCapabilities(),
          fetchReconciliationOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        await loadTasks();
      } catch (e) {
        if (!alive) return;
        setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadTasks]);

  useEffect(() => {
    if (!selected) return;
    void loadDetail(selected.id);
  }, [selected, loadDetail]);

  // ---------------------------------------------------------------- 动作

  async function guard(action: () => Promise<void>) {
    setActionError('');
    setNotice('');
    try {
      await action();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  function resetDraft() {
    setEditingId(null);
    setDraftAccountId('');
    setDraftShopId('');
    setDraftPeriodStart('');
    setDraftPeriodEnd('');
    setDraftSources([]);
    setDraftWindowDays('7');
    setDraftTolerance('0');
    setDraftNote('');
  }

  function loadDraft(task: FinanceReconTaskRow) {
    setEditingId(task.id);
    setDraftAccountId(task.accountId);
    setDraftShopId(task.shopId ?? '');
    setDraftPeriodStart(task.periodStart);
    setDraftPeriodEnd(task.periodEnd);
    setDraftSources(task.bookSources);
    setDraftWindowDays(String(task.dateWindowDays));
    setDraftTolerance(String(task.amountTolerance));
    setDraftNote(task.note);
  }

  function selectTask(task: FinanceReconTaskRow) {
    setSelected(task);
    setDetail(null);
  }

  async function submitTask() {
    const input = {
      accountId: draftAccountId,
      shopId: draftShopId === '' ? null : draftShopId,
      periodStart: draftPeriodStart,
      periodEnd: draftPeriodEnd,
      bookSources: draftSources,
      dateWindowDays: Number(draftWindowDays),
      amountTolerance: Number(draftTolerance),
      note: draftNote === '' ? null : draftNote,
    };
    return guard(async () => {
      const saved = editingId
        ? await updateReconciliationTask(editingId, input)
        : await createReconciliationTask(input);
      setSelected(saved);
      setNotice(
        editingId
          ? `已更新对账范围 ${saved.taskNo}`
          : `已建立对账任务 ${saved.taskNo}`,
      );
      resetDraft();
      await loadTasks();
      await loadDetail(saved.id);
    });
  }

  async function doMatch() {
    if (!selected) return;
    return guard(async () => {
      const result = await runReconciliationMatch(selected.id);
      setNotice(
        `匹配完成：平台账单 ${result.externalLineCount} 行 · 匹配组 ${result.matchedGroupCount} 个` +
          `（一对一 ${result.oneToOneCount} / 一对多 ${result.oneToManyCount} / ` +
          `多对一 ${result.manyToOneCount} / 多对多 ${result.manyToManyCount}）· ` +
          `差异 ${result.differenceCount} 条`,
      );
      await loadTasks();
      await loadDetail(selected.id);
    });
  }

  // ---------------------------------------------------------------- 渲染

  const accounts = options?.accounts ?? [];
  const draftReady =
    draftAccountId !== '' && draftPeriodStart !== '' && draftPeriodEnd !== '' && draftSources.length > 0;
  const matchBlockReason = selected ? reconciliationMatchBlockReason(selected) : '';
  const frozenHint = selected ? reconciliationScopeFrozenHint(selected) : '';

  return (
    <div className="space-y-6">
      <PageHeader
        title="对账任务"
        description="选择对账范围（店铺 + 周期 + 账户），运行自动匹配，输出匹配结果与差异清单。"
        actions={
          <Button
            variant="ghost"
            onClick={() => {
              void loadTasks();
            }}
            aria-label="刷新对账任务"
          >
            <RefreshCw className="size-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力声明" description="哪些真接通、哪些没交付、归谁">
          <ul className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <li key={`${c.moduleId}-${c.name}`} className="text-copy-13">
                <Badge tone={c.delivered ? 'success' : 'warning'}>
                  {c.moduleId} {c.name}
                </Badge>
                <span className="text-muted-foreground ml-2">{c.source}</span>
                <p className="text-muted-foreground mt-1">{c.note}</p>
              </li>
            ))}
          </ul>
          {capabilities.gaps.length > 0 ? (
            <div className="mt-4 space-y-2">
              <p className="text-label-13">缺口</p>
              <ul className="space-y-2">
                {capabilities.gaps.map((g) => (
                  <li key={`${g.moduleId}-${g.name}`} className="text-copy-13">
                    <Badge tone={g.delivered ? 'success' : 'warning'}>
                      {g.moduleId} {g.name}
                    </Badge>
                    <span className="text-muted-foreground ml-2">{g.owner}</span>
                    <p className="text-muted-foreground mt-1">{g.note}</p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </SectionCard>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        <SectionCard
          title="对账任务"
          description="一个账户 × 一个店铺 × 一个周期"
          className="lg:col-span-2"
        >
          {loading ? (
            <p className="text-muted-foreground text-copy-13">加载中…</p>
          ) : tasks && tasks.items.length === 0 ? (
            <EmptyState
              title={Object.keys(params).length > 0 ? '没有符合条件的对账任务' : '还没有对账任务'}
              description={
                Object.keys(params).length > 0
                  ? '换一个筛选条件试试。'
                  : '先在右边建一个任务：选账户、店铺、周期与我方账来源。'
              }
            />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="rt-account" label="账户">
                  <Input
                    value={accountFilter}
                    onChange={(e) => {
                      setAccountFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="按账户 id 过滤"
                    list="rt-account-options"
                  />
                </FormField>
                <FormField id="rt-shop" label="店铺">
                  <Input
                    value={shopFilter}
                    onChange={(e) => {
                      setShopFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="按店铺 id 过滤"
                  />
                </FormField>
                <FormField id="rt-status" label="状态">
                  <Input
                    value={statusFilter}
                    onChange={(e) => {
                      setStatusFilter(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="DRAFT / MATCHED"
                  />
                </FormField>
                <FormField id="rt-keyword" label="关键字">
                  <Input
                    value={keyword}
                    onChange={(e) => {
                      setKeyword(e.target.value);
                      setPageNo(0);
                    }}
                    placeholder="任务号 / 账户名 / 店铺名 / 备注"
                  />
                </FormField>
              </div>
              <datalist id="rt-account-options">
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.accountNo} {a.accountName}
                  </option>
                ))}
              </datalist>
              <div className="overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">任务号</th>
                      <th className="py-2 pr-3">账户</th>
                      <th className="py-2 pr-3">店铺</th>
                      <th className="py-2 pr-3">周期</th>
                      <th className="py-2 pr-3">币种</th>
                      <th className="py-2 pr-3">匹配组</th>
                      <th className="py-2 pr-3">差异</th>
                      <th className="py-2 pr-3">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks?.items.map((t) => (
                      <tr
                        key={t.id}
                        className="cursor-pointer border-b last:border-0"
                        onClick={() => selectTask(t)}
                      >
                        <td className="py-2 pr-3">{t.taskNo}</td>
                        <td className="py-2 pr-3">
                          {t.accountNo}
                          <span className="text-muted-foreground ml-1">{t.accountName}</span>
                        </td>
                        <td className="py-2 pr-3">
                          {t.shopId ? t.shopName : '全部授权店铺'}
                        </td>
                        <td className="py-2 pr-3">
                          {t.periodStart} → {t.periodEnd}
                        </td>
                        <td className="py-2 pr-3">{t.currency}</td>
                        <td className="py-2 pr-3">{t.matchedGroupCount}</td>
                        <td className="py-2 pr-3">{t.differenceCount}</td>
                        <td className="py-2 pr-3">
                          <Badge tone={reconciliationStatusTone(t.status)}>
                            {reconciliationStatusLabel(t.status)}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {tasks && tasks.total > PAGE_SIZE ? (
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    disabled={pageNo === 0}
                    onClick={() => setPageNo((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <span className="text-copy-12 text-muted-foreground">
                    第 {pageNo + 1} 页 · 共 {tasks.total} 条
                  </span>
                  <Button
                    variant="ghost"
                    disabled={(pageNo + 1) * PAGE_SIZE >= tasks.total}
                    onClick={() => setPageNo((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              ) : null}
            </div>
          )}
        </SectionCard>

        <SectionCard
          title={editingId ? '改对账范围' : '建对账任务'}
          description="币种与法人由服务端继承账户（单账户单币种）"
        >
          <div className="space-y-3">
            <FormField id="rt-new-account" label="资金账户" required>
              <Input
                value={draftAccountId}
                onChange={(e) => setDraftAccountId(e.target.value)}
                placeholder="账户 id"
                list="rt-account-options"
              />
            </FormField>
            <p className="text-muted-foreground text-copy-12">
              可选账户（来自 12.7.1.1 的真实账户档案）：
              {accounts.length === 0 ? '本租户还没有启用中的资金账户。' : ''}
            </p>
            <ul className="text-muted-foreground space-y-1 text-copy-12">
              {accounts.map((a) => (
                <li key={a.id}>
                  {a.accountNo} · {a.accountName} · {a.currency} · {a.legalEntityName}
                </li>
              ))}
            </ul>
            <FormField id="rt-new-shop" label="店铺" hint="留空 = 该账户下全部授权店铺">
              <Input
                value={draftShopId}
                onChange={(e) => setDraftShopId(e.target.value)}
                placeholder="店铺 id（可留空）"
              />
            </FormField>
            <FormField id="rt-new-start" label="周期开始" required>
              <Input
                type="date"
                value={draftPeriodStart}
                onChange={(e) => setDraftPeriodStart(e.target.value)}
              />
            </FormField>
            <FormField id="rt-new-end" label="周期结束" required>
              <Input
                type="date"
                value={draftPeriodEnd}
                onChange={(e) => setDraftPeriodEnd(e.target.value)}
              />
            </FormField>
            <div className="space-y-1">
              <p className="text-label-13">
                我方账来源
                <span className="text-destructive ml-1" aria-hidden="true">
                  *
                </span>
              </p>
              {BOOK_SOURCES.map((source) => (
                <label key={source} className="flex items-center gap-2 text-copy-13">
                  <input
                    type="checkbox"
                    checked={draftSources.includes(source)}
                    onChange={(e) =>
                      setDraftSources((prev) =>
                        e.target.checked ? [...prev, source] : prev.filter((s) => s !== source),
                      )
                    }
                  />
                  {reconciliationBookSourceLabel(source)}
                </label>
              ))}
              <p className="text-copy-12 text-muted-foreground">
                不同来源之间不互配：每个来源各自与平台账单比，选得越多差异清单越长。
              </p>
            </div>
            <FormField id="rt-new-window" label="日期窗口（天）" hint="1 ~ 31；放到几百天等于用「金额相同」冒充匹配">
              <Input
                type="number"
                min={1}
                max={31}
                value={draftWindowDays}
                onChange={(e) => setDraftWindowDays(e.target.value)}
              />
            </FormField>
            <FormField id="rt-new-tolerance" label="金额容差" hint="默认 0；容差只放宽成组时的金额相等判定">
              <Input
                type="number"
                min={0}
                step="0.01"
                value={draftTolerance}
                onChange={(e) => setDraftTolerance(e.target.value)}
              />
            </FormField>
            <FormField id="rt-new-note" label="备注">
              <Input value={draftNote} onChange={(e) => setDraftNote(e.target.value)} />
            </FormField>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => void submitTask()} disabled={!draftReady}>
                {editingId ? '保存对账范围' : '建立对账任务'}
              </Button>
              {editingId ? (
                <Button variant="ghost" onClick={resetDraft}>
                  取消
                </Button>
              ) : null}
            </div>
            <p className="text-muted-foreground text-copy-12">
              {options?.note ?? ''}
            </p>
          </div>
        </SectionCard>
      </div>

      {selected ? (
        <>
          <SectionCard
            title={`任务 ${selected.taskNo}`}
            description={reconciliationScopeLabel(selected)}
            extra={
              <div className="flex flex-wrap gap-2">
                {selected.status === 'DRAFT' ? (
                  <Button variant="ghost" onClick={() => void doMatch()}>
                    跑自动匹配
                  </Button>
                ) : null}
                {selected.status === 'DRAFT' ? (
                  <Button
                    variant="ghost"
                    onClick={() => {
                      loadDraft(selected);
                      setNotice('');
                    }}
                  >
                    改对账范围
                  </Button>
                ) : null}
              </div>
            }
          >
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="平台账单行" value={String(selected.externalLineCount)} />
              <StatCard label="匹配组" value={String(selected.matchedGroupCount)} />
              <StatCard label="匹配行" value={String(selected.matchedLineCount)} />
              <StatCard label="差异" value={String(selected.differenceCount)} />
            </div>
            <p className="text-muted-foreground mt-3 text-copy-12">
              状态：{reconciliationStatusLabel(selected.status)}；日期窗口 {selected.dateWindowDays} 天；
              金额容差 {selected.amountTolerance}；我方账来源：
              {selected.bookSources.map(reconciliationBookSourceLabel).join(' + ') || '—'}
            </p>
            {matchBlockReason ? (
              <Notice tone="info" title="匹配结果已冻结">
                {matchBlockReason}
              </Notice>
            ) : null}
            {frozenHint ? (
              <Notice tone="warning" title="对账范围已冻结">
                {frozenHint}
              </Notice>
            ) : null}
          </SectionCard>

          <SectionCard
            title="按币种汇总"
            description="🔴 跨币种绝不相加：每个币种一行，差额算不出来就写「汇率不可得」"
          >
            {detailLoading ? (
              <p className="text-muted-foreground text-copy-13">加载中…</p>
            ) : !detail || detail.summary.currencies.length === 0 ? (
              <EmptyState
                title="还没有汇总"
                description="这个任务还没跑过匹配 —— 跑一次才有平台侧、我方侧与差额。"
              />
            ) : (
              <div className="space-y-3">
                {detail.summary.currencies.map((c) => (
                  <div key={c.currency} className="border-b pb-3 last:border-0 last:pb-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="primary">{c.currency}</Badge>
                      <span className="text-copy-13">
                        平台 {formatReconciliationMoney(c.externalAmount, c.currency)} / 我方{' '}
                        {formatReconciliationMoney(c.bookAmount, c.currency)} / 差额{' '}
                        {formatReconciliationDifference(
                          c.differenceAmount,
                          c.currency,
                          c.exchangeRateAvailable,
                        )}
                      </span>
                    </div>
                    <p className="text-muted-foreground mt-1 text-copy-12">
                      匹配组 {c.matchedGroupCount} 个 · 差异 {c.differenceCount} 条
                      {c.note ? ` · ${c.note}` : ''}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="匹配结果"
            description="每行都写着「比对了哪几个维度」—— 这是「不允许金额相同即匹配」的落点"
          >
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="rt-shape" label="匹配形态">
                <Input
                  value={shapeFilter}
                  onChange={(e) => {
                    setShapeFilter(e.target.value);
                    setMatchPage(0);
                  }}
                  placeholder="ONE_TO_ONE / ONE_TO_MANY / …"
                />
              </FormField>
              <FormField id="rt-book-source" label="我方账来源">
                <Input
                  value={bookSourceFilter}
                  onChange={(e) => {
                    setBookSourceFilter(e.target.value);
                    setMatchPage(0);
                  }}
                  placeholder="RECEIVABLE / PAYMENT_ORDER / BANK_STATEMENT"
                />
              </FormField>
            </div>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button
                variant={shapeFilter === '' ? 'default' : 'ghost'}
                onClick={() => {
                  setShapeFilter('');
                  setMatchPage(0);
                }}
              >
                全部形态
              </Button>
              {SHAPES.map((shape) => (
                <Button
                  key={shape}
                  variant={shapeFilter === shape ? 'default' : 'ghost'}
                  onClick={() => {
                    setShapeFilter(shape);
                    setMatchPage(0);
                  }}
                >
                  {reconciliationShapeLabel(shape)}
                </Button>
              ))}
            </div>
            {detailLoading ? (
              <p className="text-muted-foreground mt-3 text-copy-13">加载中…</p>
            ) : !matches || matches.items.length === 0 ? (
              <EmptyState
                title="没有匹配结果"
                description="这个任务还没跑匹配，或者筛选条件下没有匹配的组。"
              />
            ) : (
              <div className="mt-3 space-y-2">
                {matches.items.map((m) => (
                  <div key={m.id} className="border-b pb-2 last:border-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={reconciliationShapeTone(m.shape)}>
                        {reconciliationShapeLabel(m.shape)}
                      </Badge>
                      <span className="text-copy-13">
                        {reconciliationExternalKindLabel(m.externalKind)}{' '}
                        {m.externalNo} {formatReconciliationMoney(m.externalAmount, m.currency)}
                      </span>
                      <span className="text-copy-13">
                        ⇄ {reconciliationBookSourceLabel(m.bookSource)} {m.bookNo}{' '}
                        {formatReconciliationMoney(m.bookAmount, m.currency)}
                      </span>
                      <Badge tone="info">{reconciliationDirectionLabel(m.direction)}</Badge>
                    </div>
                    <p className="text-muted-foreground mt-1 text-copy-12">
                      {reconciliationMatchHint(m)}
                    </p>
                    <p className="text-muted-foreground mt-1 text-copy-12">
                      平台单号 {m.externalReference || '—'} / 我方单号 {m.bookReference || '—'} ·{' '}
                      {m.externalOccurredOn ?? '—'} ⇄ {m.bookOccurredOn ?? '—'} · 组内平台{' '}
                      {formatReconciliationMoney(m.groupExternalAmount, m.currency)} / 组内我方{' '}
                      {formatReconciliationMoney(m.groupBookAmount, m.currency)} / 组内差额{' '}
                      {formatReconciliationMoney(m.groupAmountDifference, m.currency)}
                    </p>
                  </div>
                ))}
              </div>
            )}
            {matches && matches.total > PAGE_SIZE ? (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  variant="ghost"
                  disabled={matchPage === 0}
                  onClick={() => setMatchPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {matchPage + 1} 页 · 共 {matches.total} 条
                </span>
                <Button
                  variant="ghost"
                  disabled={(matchPage + 1) * PAGE_SIZE >= matches.total}
                  onClick={() => setMatchPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>

          <SectionCard title="差异清单" description="按币种分开；差额算不出来就说算不出来">
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="rt-diff-type" label="差异类型">
                <Input
                  value={differenceTypeFilter}
                  onChange={(e) => {
                    setDifferenceTypeFilter(e.target.value);
                    setDifferencePage(0);
                  }}
                  placeholder="EXTERNAL_ONLY / AMOUNT_MISMATCH / …"
                />
              </FormField>
              <FormField id="rt-diff-currency" label="币种">
                <Input
                  value={currencyFilter}
                  onChange={(e) => {
                    setCurrencyFilter(e.target.value);
                    setDifferencePage(0);
                  }}
                  placeholder="USD / EUR / …"
                />
              </FormField>
              <FormField id="rt-diff-side" label="哪一侧">
                <Input
                  value={sideFilter}
                  onChange={(e) => {
                    setSideFilter(e.target.value);
                    setDifferencePage(0);
                  }}
                  placeholder="EXTERNAL / BOOK"
                />
              </FormField>
            </div>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button
                variant={differenceTypeFilter === '' ? 'default' : 'ghost'}
                onClick={() => {
                  setDifferenceTypeFilter('');
                  setDifferencePage(0);
                }}
              >
                全部类型
              </Button>
              {DIFFERENCE_TYPES.map((type) => (
                <Button
                  key={type}
                  variant={differenceTypeFilter === type ? 'default' : 'ghost'}
                  onClick={() => {
                    setDifferenceTypeFilter(type);
                    setDifferencePage(0);
                  }}
                >
                  {reconciliationDifferenceTypeLabel(type)}
                </Button>
              ))}
            </div>
            {detailLoading ? (
              <p className="text-muted-foreground mt-3 text-copy-13">加载中…</p>
            ) : !differences || differences.items.length === 0 ? (
              <EmptyState
                title="没有差异"
                description="这个任务还没跑匹配，或者筛选条件下没有差异。"
              />
            ) : (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-copy-13">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2 pr-3">类型</th>
                      <th className="py-2 pr-3">哪一侧</th>
                      <th className="py-2 pr-3">单号</th>
                      <th className="py-2 pr-3">金额</th>
                      <th className="py-2 pr-3">差额</th>
                      <th className="py-2 pr-3">原因</th>
                      <th className="py-2 pr-3">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {differences.items.map((d) => (
                      <tr key={d.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">
                          <Badge tone={reconciliationDifferenceTone(d.differenceType)}>
                            {reconciliationDifferenceTypeLabel(d.differenceType)}
                          </Badge>
                        </td>
                        <td className="py-2 pr-3">{reconciliationSideLabel(d.side)}</td>
                        <td className="py-2 pr-3">
                          {d.reference || '—'}
                          <span className="text-muted-foreground ml-1">
                            {d.bookSource ? reconciliationBookSourceLabel(d.bookSource) : ''}
                          </span>
                        </td>
                        <td className="py-2 pr-3">
                          {formatReconciliationMoney(d.externalAmount, d.currency)} /{' '}
                          {formatReconciliationMoney(d.bookAmount, d.currency)}
                        </td>
                        <td className="py-2 pr-3">
                          {formatReconciliationDifference(
                            d.differenceAmount,
                            d.currency,
                            d.exchangeRateAvailable,
                          )}
                        </td>
                        <td className="py-2 pr-3">
                          {d.reason || '—'}
                          <span className="text-muted-foreground mt-1 block text-copy-12">
                            {reconciliationDifferenceActionHint(d)}
                          </span>
                        </td>
                        <td className="py-2 pr-3">
                          {reconciliationDifferenceStatusLabel(d.status)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {differences && differences.total > PAGE_SIZE ? (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  variant="ghost"
                  disabled={differencePage === 0}
                  onClick={() => setDifferencePage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {differencePage + 1} 页 · 共 {differences.total} 条
                </span>
                <Button
                  variant="ghost"
                  disabled={(differencePage + 1) * PAGE_SIZE >= differences.total}
                  onClick={() => setDifferencePage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>

          <SectionCard title="事件流" description="append-only 举证：谁在什么时候做了什么">
            {detailLoading ? (
              <p className="text-muted-foreground text-copy-13">加载中…</p>
            ) : !events || events.items.length === 0 ? (
              <EmptyState title="没有事件" description="这个任务还没有产生任何事件。" />
            ) : (
              <ul className="space-y-2">
                {events.items.map((e) => (
                  <li key={e.id} className="text-copy-13 border-b pb-2 last:border-0">
                    <Badge tone="info">{reconciliationEventLabel(e.eventType)}</Badge>
                    <span className="text-muted-foreground ml-2">{e.occurredAt}</span>
                    <p className="text-muted-foreground mt-1">{e.summary}</p>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </>
      ) : null}
    </div>
  );
}
