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
  FinanceAccountingCalendarRow,
  FinanceAccountingPeriodCapabilities,
  FinanceAccountingPeriodDetail,
  FinanceAccountingPeriodOptions,
  FinanceAccountingPeriodPage,
} from '../../../../../packages/api-contracts/src/index';
import type { AccountingCalendarTypeValue } from './accounting-period-model';
import {
  accountingApprovalActionLabel,
  accountingCloseStateText,
  accountingFiscalPositionText,
  accountingPeriodEventText,
  accountingPeriodGenerateSummary,
  accountingPeriodRangeText,
  accountingPeriodStatusLabel,
  accountingPeriodStatusTone,
  accountingPeriodTypeLabel,
  accountingReopenActuallyApplied,
  accountingReopenOutcomeText,
  accountingReopenOutcomeTone,
  closeAccountingPeriod,
  createAccountingCalendar,
  createAccountingPeriod,
  fetchAccountingCalendars,
  fetchAccountingPeriod,
  fetchAccountingPeriodCapabilities,
  fetchAccountingPeriodOptions,
  fetchAccountingPeriods,
  generateAccountingPeriods,
  reopenAccountingPeriod,
  updateAccountingCalendar,
} from './accounting-period-model';

/**
 * 12.5.1.2 会计期间。
 *
 * <p>蓝图 12-finance.md：「管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、
 * 关账与重开、期间内凭证限制。」输入：「会计日历配置；关账/重开操作；审批。」输出：「期间状态；关账记录。」
 * 关系：「上游 = 会计日历；下游 = 12.5.2.1 凭证（已关闭期间禁止过账）。**重开已关期间是高风险操作**，
 * 必须审批留痕。」
 *
 * <p><b>页面围绕四条纪律组织</b>：
 *
 * <ol>
 *   <li><b>期间身份只读</b>：期间编码 / 起止日 / 本位币 / 会计日历 / fiscal 周期**一经入账不可改**
 *       （凭证按期间引用它，改了等于改账期）⇒ 页面只显示这些字段，**不提供**改它们的输入框。</li>
 *   <li><b>重开走审批，四种结局都如实显示</b>：提交申请 / 审批中 / 已驳回 / 已重开 ——
 *       只有最后一种期间才真的开放，其余三种**仍然关闭**，页面**绝不**显示成「已重开」了事。</li>
 *   <li><b>关账必须写原因</b>，没有原因就不提交（服务端也会拒）；关账后页面显示
 *       「已关账（原因）」而不是只显示一个状态标签。</li>
 *   <li><b>关账检查清单不归本页</b>：属 12.5.1.3（未交付）⇒ 能力声明如实标出不可用，
 *       页面**不假装**跑过清单。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有期间」。
 */
export function AccountingPeriodWorkspace() {
  const [caps, setCaps] = useState<FinanceAccountingPeriodCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceAccountingPeriodOptions | null>(null);
  const [page, setPage] = useState<FinanceAccountingPeriodPage | null>(null);
  const [calendars, setCalendars] = useState<FinanceAccountingCalendarRow[]>([]);
  const [detail, setDetail] = useState<FinanceAccountingPeriodDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [periodType, setPeriodType] = useState('');
  const [calendarId, setCalendarId] = useState('');
  const [fiscalYear, setFiscalYear] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 手工登记自定义期间
  const [code, setCode] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [note, setNote] = useState('');

  // 新建日历
  const [calendarCode, setCalendarCode] = useState('');
  const [calendarName, setCalendarName] = useState('');
  const [calendarType, setCalendarType] = useState<AccountingCalendarTypeValue>('CALENDAR_MONTH');
  const [startMonth, setStartMonth] = useState('1');
  const [anchorDate, setAnchorDate] = useState('');
  const [calendarCurrency, setCalendarCurrency] = useState('USD');

  // 生成期间
  const [generateCalendarId, setGenerateCalendarId] = useState('');
  const [generateYear, setGenerateYear] = useState('');

  // 关账 / 重开
  const [closeTarget, setCloseTarget] = useState('');
  const [closeReason, setCloseReason] = useState('');
  const [reopenTarget, setReopenTarget] = useState('');
  const [reopenReason, setReopenReason] = useState('');
  const [reopenOutcome, setReopenOutcome] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = {
        status: status || undefined,
        periodType: periodType || undefined,
        calendarId: calendarId || undefined,
        fiscalYear: fiscalYear ? Number(fiscalYear) : undefined,
        keyword: keyword || undefined,
        page: currentPage,
        size: 50,
      };
      const [capsData, optionsData, pageData, calendarData] = await Promise.all([
        fetchAccountingPeriodCapabilities(),
        fetchAccountingPeriodOptions(),
        fetchAccountingPeriods(params),
        fetchAccountingCalendars(),
      ]);
      setCaps(capsData);
      setOptions(optionsData);
      setPage(pageData);
      setCalendars(calendarData);
      if (!generateCalendarId && calendarData.length > 0) setGenerateCalendarId(calendarData[0]!.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [status, periodType, calendarId, fiscalYear, keyword, currentPage, generateCalendarId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runCreatePeriod() {
    setActionError('');
    setNotice('');
    if (!code.trim()) {
      setActionError('期间编码不能为空');
      return;
    }
    if (!start || !end) {
      setActionError('期间起止日期不能为空');
      return;
    }
    if (end < start) {
      setActionError('期间结束日不能早于开始日');
      return;
    }
    try {
      const created = await createAccountingPeriod({
        periodCode: code.trim(),
        periodStart: start,
        periodEnd: end,
        baseCurrency: currency.trim().toUpperCase(),
        note: note.trim(),
      });
      setNotice(`已登记自定义期间 ${created.periodCode}`);
      setCode('');
      setStart('');
      setEnd('');
      setNote('');
      await load();
    } catch (e) {
      // ⚠️ 「编码重复」「本位币不合法」这类阻断原因由服务端给出，原样显示。
      setActionError(e instanceof Error ? e.message : '登记期间失败');
    }
  }

  async function runCreateCalendar() {
    setActionError('');
    setNotice('');
    if (!calendarCode.trim() || !calendarName.trim()) {
      setActionError('日历编码与名称都不能为空');
      return;
    }
    if (calendarType === 'FISCAL_445' && !anchorDate) {
      setActionError('4-4-5 日历必须给出锚点日（财年起始日模板）');
      return;
    }
    try {
      const created = await createAccountingCalendar({
        calendarCode: calendarCode.trim(),
        calendarName: calendarName.trim(),
        calendarType: calendarType,
        fiscalYearStartMonth: Number(startMonth) || 1,
        anchorDate: calendarType === 'FISCAL_445' ? anchorDate : null,
        baseCurrency: calendarCurrency.trim().toUpperCase(),
        note: '',
      });
      setNotice(`已建会计日历 ${created.calendarCode}（${created.calendarTypeLabel}）`);
      setCalendarCode('');
      setCalendarName('');
      setAnchorDate('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '建日历失败');
    }
  }

  async function runGenerate() {
    setActionError('');
    setNotice('');
    if (!generateCalendarId) {
      setActionError('先选一套会计日历');
      return;
    }
    if (!generateYear) {
      setActionError('必须指定要生成的财年');
      return;
    }
    try {
      const result = await generateAccountingPeriods(generateCalendarId, {
        fiscalYear: Number(generateYear),
      });
      setNotice(accountingPeriodGenerateSummary(result));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成期间失败');
    }
  }

  async function runToggleCalendar(calendar: FinanceAccountingCalendarRow) {
    setActionError('');
    setNotice('');
    try {
      await updateAccountingCalendar(calendar.id, {
        calendarName: calendar.calendarName,
        status: calendar.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
        note: calendar.note,
      });
      setNotice(
        calendar.status === 'ACTIVE'
          ? `已停用日历 ${calendar.calendarCode}`
          : `已启用日历 ${calendar.calendarCode}`,
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '改日历失败');
    }
  }

  async function runClose() {
    setActionError('');
    setNotice('');
    if (!closeTarget) {
      setActionError('先选要关闭的期间');
      return;
    }
    if (!closeReason.trim()) {
      setActionError('关闭原因不能为空：关账必须说得清为什么关');
      return;
    }
    const target = page?.items.find((p) => p.id === closeTarget);
    if (!target) {
      setActionError('找不到要关闭的期间（请刷新列表）');
      return;
    }
    try {
      const closed = await closeAccountingPeriod(closeTarget, {
        reason: closeReason.trim(),
        expectedVersion: target.version,
      });
      setNotice(`会计期间 ${closed.periodCode} 已关账`);
      setCloseTarget('');
      setCloseReason('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '关账失败');
    }
  }

  async function runReopen() {
    setActionError('');
    setNotice('');
    setReopenOutcome(null);
    if (!reopenTarget) {
      setActionError('先选要重开的期间');
      return;
    }
    if (!reopenReason.trim()) {
      setActionError('重开原因不能为空');
      return;
    }
    const target = page?.items.find((p) => p.id === reopenTarget);
    if (!target) {
      setActionError('找不到要重开的期间（请刷新列表）');
      return;
    }
    const targetId = reopenTarget;
    try {
      const result = await reopenAccountingPeriod(targetId, {
        reason: reopenReason.trim(),
        expectedVersion: target.version,
      });
      // 🔴 **四种结局都如实显示**：只有 REOPENED 才真的开了。
      setReopenOutcome(result.outcome);
      setNotice(
        `${accountingReopenOutcomeText(result.outcome)}：${result.message}${
          accountingReopenActuallyApplied(result.outcome) ? '' : '（期间仍然关闭）'
        }`,
      );
      setReopenTarget('');
      setReopenReason('');
      await load();
      setDetail(await fetchAccountingPeriod(targetId));
    } catch (e) {
      // ⚠️ 「没有生效审批流」「审批不可达」这类阻断原因由服务端给出，原样显示。
      setActionError(e instanceof Error ? e.message : '重开失败');
    }
  }

  async function runShowDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchAccountingPeriod(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取期间详情失败');
    }
  }

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const openCount = page?.summaryByStatus.find((s) => s.status === 'OPEN')?.count ?? 0;
  const closedCount = page?.summaryByStatus.find((s) => s.status === 'CLOSED')?.count ?? 0;
  const calendarCount = calendars.length;

  return (
    <div className="space-y-6">
      <PageHeader
        title="会计期间"
        description="会计日历（自然月 / 4-4-5 fiscal 周期）· 期间状态 · 关账与重开 · 关账记录"
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
          {reopenOutcome ? (
            <span className="inline-flex items-center gap-2">
              <Badge tone={accountingReopenOutcomeTone(reopenOutcome)}>
                {accountingReopenOutcomeText(reopenOutcome)}
              </Badge>
              {notice}
            </span>
          ) : (
            notice
          )}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 能力声明：没交付的事必须说在界面上 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明"
              description="🔴 标红的这几项**当前不可用** —— 界面不提供它们的入口，也不拿假数据顶替。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                      <Badge tone="danger">不可用</Badge>
                      <span>
                        <b>{c.name}</b>
                        {c.ownerModule ? `（归属模块 ${c.ownerModule}）` : ''}：{c.detail}
                      </span>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="期间" value={page?.total ?? 0} unit="个" comparisonLabel="按当前筛选条件" />
            <StatCard label="开放" value={openCount} unit="个" comparisonLabel="可以过账" />
            <StatCard label="已关闭" value={closedCount} unit="个" comparisonLabel="禁止过账" />
            <StatCard label="会计日历" value={calendarCount} unit="套" comparisonLabel="账期体系" />
          </div>

          {/* ---------- 筛选 ---------- */}
          <SectionCard title="筛选" description="筛选条件全部来自真实数据（不是写死的词表）。">
            <div className="grid gap-3 md:grid-cols-3">
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
                    {accountingPeriodStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={periodType}
                onChange={(e) => {
                  setPeriodType(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部期间类型</option>
                {(options?.periodTypes ?? []).map((t) => (
                  <option key={t} value={t}>
                    {accountingPeriodTypeLabel(t)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={calendarId}
                onChange={(e) => {
                  setCalendarId(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部日历</option>
                {calendars.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.calendarName}（{c.calendarCode}）
                  </option>
                ))}
              </select>
              <Input
                value={fiscalYear}
                onChange={(e) => {
                  setFiscalYear(e.target.value.replace(/\D/g, ''));
                  setCurrentPage(0);
                }}
                placeholder="财年（可空）"
              />
              <Input
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
                placeholder="按期间编码 / 关账原因 / 重开原因搜索"
              />
            </div>
          </SectionCard>

          {/* ---------- 会计日历 ---------- */}
          <SectionCard
            title="会计日历"
            description="一套日历 = 一种账期体系：自然月按财年起始月推 12 个日历月；4-4-5 按锚点日推 12 个 13 周周期。"
          >
            {calendars.length === 0 ? (
              <EmptyState
                title="还没有会计日历"
                description="先建一套日历（自然月或 4-4-5），再从它生成一个财年的期间。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">编码</th>
                    <th className="py-2">名称</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">财年起始月</th>
                    <th className="py-2">锚点日</th>
                    <th className="py-2">本位币</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {calendars.map((c) => (
                    <tr key={c.id} className="border-border border-t">
                      <td className="py-2 font-medium">{c.calendarCode}</td>
                      <td className="py-2">{c.calendarName}</td>
                      <td className="py-2">{c.calendarTypeLabel}</td>
                      <td className="py-2">{c.fiscalYearStartMonth} 月</td>
                      <td className="py-2">{c.anchorDate ?? '—'}</td>
                      <td className="py-2">{c.baseCurrency}</td>
                      <td className="py-2">
                        <Badge tone={c.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {c.statusLabel}
                        </Badge>
                      </td>
                      <td className="py-2">
                        <Button size="sm" variant="outline" onClick={() => void runToggleCalendar(c)}>
                          {c.status === 'ACTIVE' ? '停用' : '启用'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <div className="mt-4 grid gap-3 md:grid-cols-3">
              <Input
                value={calendarCode}
                onChange={(e) => setCalendarCode(e.target.value)}
                placeholder="日历编码（租户内唯一）"
              />
              <Input
                value={calendarName}
                onChange={(e) => setCalendarName(e.target.value)}
                placeholder="日历名称"
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={calendarType}
                onChange={(e) => setCalendarType(e.target.value as AccountingCalendarTypeValue)}
              >
                <option value="CALENDAR_MONTH">自然月</option>
                <option value="FISCAL_445">4-4-5 周期</option>
                <option value="CUSTOM">自定义（不生成期间）</option>
              </select>
              <Input
                value={startMonth}
                onChange={(e) => setStartMonth(e.target.value.replace(/\D/g, ''))}
                placeholder="财年起始月（1-12，自然月日历用）"
              />
              <Input
                value={anchorDate}
                onChange={(e) => setAnchorDate(e.target.value)}
                placeholder="锚点日 2026-04-06（4-4-5 必填）"
                disabled={calendarType !== 'FISCAL_445'}
              />
              <Input
                value={calendarCurrency}
                onChange={(e) => setCalendarCurrency(e.target.value)}
                placeholder="本位币 USD"
              />
            </div>
            <div className="mt-3">
              <Button onClick={() => void runCreateCalendar()}>建日历</Button>
            </div>

            <div className="mt-6 grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={generateCalendarId}
                onChange={(e) => setGenerateCalendarId(e.target.value)}
              >
                <option value="">（选择日历）</option>
                {calendars.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.calendarName}（{c.calendarCode}）
                  </option>
                ))}
              </select>
              <Input
                value={generateYear}
                onChange={(e) => setGenerateYear(e.target.value.replace(/\D/g, ''))}
                placeholder="要生成的财年（如 2026）"
              />
              <div>
                <Button variant="secondary" onClick={() => void runGenerate()}>
                  按日历生成期间
                </Button>
              </div>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              ⚠️ 已存在的期间编码**被跳过而不是覆盖** —— 重跑不会冲掉用户手改过的期间。
            </p>
          </SectionCard>

          {/* ---------- 手工登记自定义期间 ---------- */}
          <SectionCard
            title="手工登记自定义期间"
            description="自定义期间**不绑定会计日历** ⇒ 没有 fiscal 周期。要有 fiscal 周期请用上面的「按日历生成期间」。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <Input
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="期间编码（如 2026-10）"
              />
              <Input
                type="date"
                value={start}
                onChange={(e) => setStart(e.target.value)}
                placeholder="开始日"
              />
              <Input
                type="date"
                value={end}
                onChange={(e) => setEnd(e.target.value)}
                placeholder="结束日"
              />
              <Input
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                placeholder="本位币 USD"
              />
              <Input
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="备注（可空）"
              />
            </div>
            <div className="mt-3">
              <Button onClick={() => void runCreatePeriod()}>登记期间</Button>
            </div>
          </SectionCard>

          {/* ---------- 关账 ---------- */}
          <SectionCard
            title="关账"
            description="🔴 关账**必须写明原因**（服务端也会拒）；关账后该期间**禁止过账**（下游 12.5.2.1 凭证的过账闸门读的就是这个状态）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={closeTarget}
                onChange={(e) => setCloseTarget(e.target.value)}
              >
                <option value="">（选择要关闭的期间）</option>
                {(page?.items ?? [])
                  .filter((p) => p.status === 'OPEN')
                  .map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.periodCode}（{p.periodStart} ~ {p.periodEnd}）
                    </option>
                  ))}
              </select>
              <Input
                value={closeReason}
                onChange={(e) => setCloseReason(e.target.value)}
                placeholder="关闭原因（必填）"
              />
              <div>
                <Button onClick={() => void runClose()}>关闭期间</Button>
              </div>
            </div>
          </SectionCard>

          {/* ---------- 重开 ---------- */}
          <SectionCard
            title="重开已关闭期间"
            description="🔴 **重开已关期间是高风险操作，必须审批留痕**：本模块不造第二套审批引擎，重开走 17 审批（identity）。没有生效审批流 ⇒ 拒绝重开；审批读不到 ⇒ 整个操作失败、期间保持关闭。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={reopenTarget}
                onChange={(e) => setReopenTarget(e.target.value)}
              >
                <option value="">（选择要重开的期间）</option>
                {(page?.items ?? [])
                  .filter((p) => p.status === 'CLOSED')
                  .map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.periodCode}（{p.periodStart} ~ {p.periodEnd}）
                    </option>
                  ))}
              </select>
              <Input
                value={reopenReason}
                onChange={(e) => setReopenReason(e.target.value)}
                placeholder="重开原因（必填）"
              />
              <div>
                <Button variant="destructive" onClick={() => void runReopen()}>
                  申请重开
                </Button>
              </div>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              ⚠️ 提交后期间**仍然关闭**；审批通过后再调用一次本操作才真正重开。
            </p>
          </SectionCard>

          {/* ---------- 期间列表 ---------- */}
          <SectionCard
            title="会计期间"
            description="期间身份（编码 / 区间 / 本位币 / 日历 / fiscal 周期）**一经入账不可改** —— 凭证按期间引用它。"
          >
            {page && page.items.length === 0 ? (
              <EmptyState
                title="没有匹配的期间"
                description={
                  page.total === 0
                    ? '当前还没有期间 —— 先建一套会计日历并生成一个财年的期间，或者手工登记一个自定义期间。'
                    : '当前筛选条件下没有期间，换个条件试试。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">期间编码</th>
                    <th className="py-2">区间</th>
                    <th className="py-2">周期长度</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">fiscal 位置</th>
                    <th className="py-2">日历</th>
                    <th className="py-2">本位币</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {(page?.items ?? []).map((p) => (
                    <tr key={p.id} className="border-border border-t">
                      <td className="py-2 font-medium">{p.periodCode}</td>
                      <td className="py-2">
                        {p.periodStart} ~ {p.periodEnd}
                      </td>
                      <td className="py-2">{p.periodLengthDays} 天</td>
                      <td className="py-2">{p.periodTypeLabel}</td>
                      <td className="py-2">{accountingFiscalPositionText(p)}</td>
                      <td className="py-2">{p.calendarCode ?? '—'}</td>
                      <td className="py-2">{p.baseCurrency}</td>
                      <td className="py-2">
                        <Badge tone={accountingPeriodStatusTone(p.status)}>{p.statusLabel}</Badge>
                        <div className="text-muted-foreground mt-1 text-xs">
                          {accountingCloseStateText(p)}
                        </div>
                      </td>
                      <td className="py-2">
                        <Button size="sm" variant="outline" onClick={() => void runShowDetail(p.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <div className="mt-3 flex items-center gap-3 text-sm">
              <Button
                size="sm"
                variant="secondary"
                disabled={currentPage <= 0}
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span>
                第 {currentPage + 1} / {totalPages} 页 · 共 {page?.total ?? 0} 个期间
              </span>
              <Button
                size="sm"
                variant="secondary"
                disabled={currentPage + 1 >= totalPages}
                onClick={() => setCurrentPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 期间详情：关账记录 + 审批链路 ---------- */}
          {detail && (
            <SectionCard
              title={`期间详情 · ${detail.period.periodCode}`}
              description="期间 + 关账记录（append-only 留痕）+ 重开审批链路"
              extra={
                <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
                  收起
                </Button>
              }
            >
              <div className="grid gap-3 md:grid-cols-4">
                <div>
                  <div className="text-muted-foreground text-xs">状态</div>
                  <Badge tone={accountingPeriodStatusTone(detail.period.status)}>
                    {detail.period.statusLabel}
                  </Badge>
                </div>
                <div>
                  <div className="text-muted-foreground text-xs">区间</div>
                  <div>{accountingPeriodRangeText(detail.period)}</div>
                </div>
                <div>
                  <div className="text-muted-foreground text-xs">fiscal 位置</div>
                  <div>{accountingFiscalPositionText(detail.period)}</div>
                </div>
                <div>
                  <div className="text-muted-foreground text-xs">关账 / 重开</div>
                  <div>{accountingCloseStateText(detail.period)}</div>
                </div>
              </div>

              <h4 className="mt-4 text-sm font-medium">关账记录（期间事件留痕）</h4>
              {detail.events.length === 0 ? (
                <p className="text-muted-foreground text-sm">这个期间还没有任何留痕。</p>
              ) : (
                <ul className="mt-2 space-y-2 text-sm">
                  {detail.events.map((e) => (
                    <li key={e.id} className="border-border border-t pt-2">
                      <div className="flex items-center gap-2">
                        <Badge tone="info">{e.eventTypeLabel}</Badge>
                        <span className="text-muted-foreground">{e.occurredAt}</span>
                        {e.approvalStatus ? (
                          <Badge tone={e.approvalStatus === 'APPROVED' ? 'success' : 'warning'}>
                            审批 {e.approvalStatus}
                          </Badge>
                        ) : null}
                      </div>
                      <div className="mt-1">{accountingPeriodEventText(e)}</div>
                    </li>
                  ))}
                </ul>
              )}

              <h4 className="mt-4 text-sm font-medium">重开审批链路</h4>
              {detail.approvals.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有审批留痕 —— 重开必须先走 17 审批（identity），审批通过后这里会出现完整链路。
                </p>
              ) : (
                <table className="text-copy-13 mt-2 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">动作</th>
                      <th className="py-2">节点</th>
                      <th className="py-2">审批人</th>
                      <th className="py-2">意见</th>
                      <th className="py-2">决定时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.approvals.map((a) => (
                      <tr key={a.id} className="border-border border-t">
                        <td className="py-2">
                          <Badge
                            tone={
                              a.action === 'APPROVE'
                                ? 'success'
                                : a.action === 'REJECT'
                                  ? 'danger'
                                  : 'neutral'
                            }
                          >
                            {accountingApprovalActionLabel(a.action)}
                          </Badge>
                        </td>
                        <td className="py-2">{a.nodeName}</td>
                        <td className="py-2">{a.actorName || '—'}</td>
                        <td className="py-2">{a.comment || '—'}</td>
                        <td className="py-2">{a.decidedAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
