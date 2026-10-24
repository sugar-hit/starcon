'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
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
} from '@starcon/ui';
import {
  BellRing,
  CalendarClock,
  CircleAlert,
  ClipboardCheck,
  Loader2,
  Plus,
  RefreshCw,
  ScrollText,
  TriangleAlert,
} from 'lucide-react';
import {
  FILING_CYCLES,
  REMINDER_KINDS,
  STATUSES,
  TAX_TYPES,
  acknowledgeReminder,
  advanceEntry,
  canAdvance,
  createCycleConfig,
  createEntry,
  deadlineText,
  emptyStateKind,
  emptyStateText,
  fetchCapabilities,
  fetchCycleConfigs,
  fetchEntries,
  fetchOptions,
  fetchReminders,
  filingCycleLabel,
  generateEntries,
  readinessText,
  refreshReadiness,
  reminderKindLabel,
  responsibleText,
  statusLabel,
  statusTone,
  taxTypeLabel,
  updateCycleConfig,
  updateEntry,
  urgencyLabel,
  urgencyOf,
  urgencyTone,
  type EntryFilters,
  type FilingCalendarAdvanceInput,
  type FilingCalendarCapabilities,
  type FilingCalendarCycleConfigInput,
  type FilingCalendarCycleConfigPage,
  type FilingCalendarEntryInput,
  type FilingCalendarEntryPage,
  type FilingCalendarEntryView,
  type FilingCalendarOptions,
  type FilingCalendarReminderPage,
  type FilingCalendarReminderView,
} from './filing-calendar-model';

/**
 * 16.3.1.2 申报日历 工作区。
 *
 * 蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * **漏报罚款远高于系统能提供的价值**，日历提醒是最低成本的合规保障。」
 *
 * 🔴 页面上的七条纪律：
 *
 * 1. **逾期 / 临近截止排在最前**（后端已排好序，前端不自己重排，只如实标注紧迫度）。
 * 2. **「读不到」显示成「读不到」**：12.6.1.2 读不到时显示「读不到」并给出原因，
 *    **不显示成「数据没就绪」**。
 * 3. **截止日显示当地时刻**（德国 23:59 截止就是德国当地时间，不换算成浏览器时区）。
 * 4. **「已提交 / 已确认」必须填凭据号**：系统不代造受理号，没凭据号就不给推进入口。
 * 5. **辖区从受控码表选，不自由输入**（值域钉死在 DB 层）。
 * 6. **提醒清单如实报总数**：分页截断时写明「只取回前 N 条（共 M 条）」——
 *    到期提醒不能被分页吞掉。
 * 7. **三态空态各不相同**：没有配置 / 有配置没条目 / 筛选无结果，分开说。
 */
const PAGE_SIZE = 10;

interface ConfigForm {
  jurisdictionCode: string;
  taxType: string;
  filingCycle: string;
  periodAnchorDay: string;
  dueOffsetDays: string;
  dueHour: string;
  dueTimezone: string;
  reminderLeadDays: string;
  effectiveFrom: string;
  note: string;
}

const EMPTY_CONFIG: ConfigForm = {
  jurisdictionCode: '',
  taxType: 'VAT',
  filingCycle: 'MONTHLY',
  periodAnchorDay: '1',
  dueOffsetDays: '10',
  dueHour: '23',
  dueTimezone: 'Europe/Berlin',
  reminderLeadDays: '7',
  effectiveFrom: '',
  note: '',
};

interface EntryForm {
  jurisdictionCode: string;
  taxType: string;
  filingCycle: string;
  periodStart: string;
  periodEnd: string;
  dueAt: string;
  responsibleMembershipId: string;
  reminderLeadDays: string;
  requiredDataText: string;
  note: string;
}

const EMPTY_ENTRY: EntryForm = {
  jurisdictionCode: '',
  taxType: 'VAT',
  filingCycle: 'MONTHLY',
  periodStart: '',
  periodEnd: '',
  dueAt: '',
  responsibleMembershipId: '',
  reminderLeadDays: '',
  requiredDataText: '',
  note: '',
};

export function FilingCalendarWorkspace() {
  const [caps, setCaps] = useState<FilingCalendarCapabilities | null>(null);
  const [options, setOptions] = useState<FilingCalendarOptions | null>(null);
  const [entries, setEntries] = useState<FilingCalendarEntryPage | null>(null);
  const [reminders, setReminders] = useState<FilingCalendarReminderPage | null>(null);
  const [configs, setConfigs] = useState<FilingCalendarCycleConfigPage | null>(null);

  const [jurisdictionCode, setJurisdictionCode] = useState('');
  const [taxType, setTaxType] = useState('');
  const [filingCycle, setFilingCycle] = useState('');
  const [status, setStatus] = useState('');
  const [entryPage, setEntryPage] = useState(0);
  const [reminderPage, setReminderPage] = useState(0);

  const [configOpen, setConfigOpen] = useState(false);
  const [configForm, setConfigForm] = useState<ConfigForm>(EMPTY_CONFIG);
  const [configEditing, setConfigEditing] = useState<string | null>(null);

  const [entryOpen, setEntryOpen] = useState(false);
  const [entryForm, setEntryForm] = useState<EntryForm>(EMPTY_ENTRY);
  const [entryEditing, setEntryEditing] = useState<FilingCalendarEntryView | null>(null);

  const [advanceTarget, setAdvanceTarget] = useState<FilingCalendarEntryView | null>(null);
  const [advanceStatus, setAdvanceStatus] = useState('SUBMITTED');
  const [advanceReference, setAdvanceReference] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const filters: EntryFilters = useMemo(
    () => ({ jurisdictionCode, taxType, filingCycle, status }),
    [jurisdictionCode, taxType, filingCycle, status],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionsResult, entryResult, reminderResult, configResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchOptions(),
          fetchEntries(entryPage, PAGE_SIZE, filters),
          fetchReminders(reminderPage, PAGE_SIZE),
          fetchCycleConfigs(0, 20),
        ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setEntries(entryResult);
      setReminders(reminderResult);
      setConfigs(configResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [entryPage, reminderPage, filters]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const applyFilters = useCallback(
    (next: Partial<{ jurisdictionCode: string; taxType: string; filingCycle: string; status: string }>) => {
      setJurisdictionCode(next.jurisdictionCode ?? jurisdictionCode);
      setTaxType(next.taxType ?? taxType);
      setFilingCycle(next.filingCycle ?? filingCycle);
      setStatus(next.status ?? status);
      setEntryPage(0);
    },
    [jurisdictionCode, taxType, filingCycle, status],
  );

  const openConfigCreate = useCallback(() => {
    setConfigEditing(null);
    setConfigForm(EMPTY_CONFIG);
    setConfigOpen(true);
  }, []);

  const openConfigEdit = useCallback((id: string) => {
    const item = configs?.items.find((row) => row.id === id);
    if (!item) return;
    setConfigEditing(item.id);
    setConfigForm({
      jurisdictionCode: item.jurisdictionCode,
      taxType: item.taxType,
      filingCycle: item.filingCycle,
      periodAnchorDay: String(item.periodAnchorDay),
      dueOffsetDays: String(item.dueOffsetDays),
      dueHour: String(item.dueHour),
      dueTimezone: item.dueTimezone,
      reminderLeadDays: String(item.reminderLeadDays),
      effectiveFrom: item.effectiveFrom,
      note: item.note,
    });
    setConfigOpen(true);
  }, [configs]);

  const submitConfig = useCallback(
    () =>
      run(async () => {
        if (!configForm.jurisdictionCode) throw new Error('必须选辖区');
        const input: FilingCalendarCycleConfigInput = {
          jurisdictionCode: configForm.jurisdictionCode,
          taxType: configForm.taxType,
          filingCycle: configForm.filingCycle,
          periodAnchorDay: Number(configForm.periodAnchorDay),
          dueOffsetDays: Number(configForm.dueOffsetDays),
          dueHour: Number(configForm.dueHour),
          dueTimezone: configForm.dueTimezone.trim(),
          reminderLeadDays: Number(configForm.reminderLeadDays),
          note: configForm.note.trim(),
        };
        // ⚠️ 精确可选属性（exactOptionalPropertyTypes）：**不能**显式传 undefined，
        //    只能「有值才放这个键」。
        if (configForm.effectiveFrom.trim()) input.effectiveFrom = configForm.effectiveFrom.trim();
        if (configEditing) {
          await updateCycleConfig(configEditing, input);
        } else {
          await createCycleConfig(input);
        }
        setConfigOpen(false);
        setConfigEditing(null);
        setConfigForm(EMPTY_CONFIG);
        await refresh();
      }),
    [run, configForm, configEditing, refresh],
  );

  const openEntryCreate = useCallback(() => {
    setEntryEditing(null);
    setEntryForm(EMPTY_ENTRY);
    setEntryOpen(true);
  }, []);

  const openEntryEdit = useCallback((item: FilingCalendarEntryView) => {
    setEntryEditing(item);
    setEntryForm({
      jurisdictionCode: item.jurisdictionCode,
      taxType: item.taxType,
      filingCycle: item.filingCycle,
      periodStart: item.periodStart,
      periodEnd: item.periodEnd,
      dueAt: item.dueAt,
      responsibleMembershipId: item.responsibleMembershipId,
      reminderLeadDays: item.reminderLeadDays ? String(item.reminderLeadDays) : '',
      requiredDataText: item.requiredData
        .map((row) => `${row.code}|${row.label}|${row.required ? '必需' : '可选'}|${row.source}`)
        .join('\n'),
      note: item.note,
    });
    setEntryOpen(true);
  }, []);

  const submitEntry = useCallback(
    () =>
      run(async () => {
        if (!entryForm.jurisdictionCode) throw new Error('必须选辖区');
        if (!entryForm.periodStart || !entryForm.periodEnd) {
          throw new Error('必须填申报期起止');
        }
        const requiredData = entryForm.requiredDataText
          .split('\n')
          .map((line) => line.trim())
          .filter(Boolean)
          .map((line) => {
            const [code, label, required, source] = line.split('|');
            return {
              code: (code ?? '').trim(),
              label: (label ?? code ?? '').trim(),
              required: (required ?? '必需').trim() === '必需',
              source: (source ?? '').trim(),
            };
          });
        const input: FilingCalendarEntryInput = {
          jurisdictionCode: entryForm.jurisdictionCode,
          taxType: entryForm.taxType,
          filingCycle: entryForm.filingCycle,
          periodStart: entryForm.periodStart.trim(),
          periodEnd: entryForm.periodEnd.trim(),
          requiredData,
          note: entryForm.note.trim(),
        };
        // ⚠️ 精确可选属性（exactOptionalPropertyTypes）：**不能**显式传 undefined。
        if (entryForm.dueAt.trim()) input.dueAt = entryForm.dueAt.trim();
        if (entryForm.responsibleMembershipId.trim())
          input.responsibleMembershipId = entryForm.responsibleMembershipId.trim();
        if (entryForm.reminderLeadDays.trim())
          input.reminderLeadDays = Number(entryForm.reminderLeadDays);
        if (entryEditing) {
          await updateEntry(entryEditing.id, input);
        } else {
          await createEntry(input);
        }
        setEntryOpen(false);
        setEntryEditing(null);
        setEntryForm(EMPTY_ENTRY);
        await refresh();
      }),
    [run, entryForm, entryEditing, refresh],
  );

  const submitAdvance = useCallback(
    () =>
      run(async () => {
        if (!advanceTarget) return;
        const advanceInput: FilingCalendarAdvanceInput = { targetStatus: advanceStatus };
        // ⚠️ 精确可选属性：凭据号没填就**不放这个键**（后端会明确报「必须带凭据号」）。
        if (advanceReference.trim()) advanceInput.reference = advanceReference.trim();
        await advanceEntry(advanceTarget.id, advanceInput);
        setAdvanceTarget(null);
        setAdvanceReference('');
        await refresh();
      }),
    [run, advanceTarget, advanceStatus, advanceReference, refresh],
  );

  const doGenerate = useCallback(
    () =>
      run(async () => {
        const result = await generateEntries();
        setNotice(result.note);
        await refresh();
      }),
    [run, refresh],
  );

  const doRefreshReadiness = useCallback(
    () =>
      run(async () => {
        const result = await refreshReadiness();
        setNotice(result.note);
        await refresh();
      }),
    [run, refresh],
  );

  const doAcknowledge = useCallback(
    (id: string) =>
      run(async () => {
        await acknowledgeReminder(id);
        await refresh();
      }),
    [run, refresh],
  );

  /** 🔴 空态三态：没有配置 ≠ 有配置没条目 ≠ 筛选无结果。 */
  const emptyKind = useMemo(
    () =>
      emptyStateKind({
        totalConfigs: configs?.total ?? 0,
        totalEntries: entries?.total ?? 0,
        filtered: Boolean(jurisdictionCode || taxType || filingCycle || status),
      }),
    [configs, entries, jurisdictionCode, taxType, filingCycle, status],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载申报日历…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="申报日历">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="申报日历加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="申报日历"
        description="各辖区的申报周期、截止日、所需数据、状态与责任人。漏报罚款远高于系统能提供的价值，日历提醒是最低成本的合规保障。"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button variant="outline" onClick={doRefreshReadiness} disabled={busy}>
              <ClipboardCheck className="mr-2 size-4" />
              刷新数据就绪
            </Button>
            <Button variant="outline" onClick={doGenerate} disabled={busy}>
              <CalendarClock className="mr-2 size-4" />
              按配置生成当前申报期
            </Button>
            <Button variant="outline" onClick={openConfigCreate}>
              <Plus className="mr-2 size-4" />
              申报周期配置
            </Button>
            <Button onClick={openEntryCreate}>
              <ScrollText className="mr-2 size-4" />
              登记申报义务
            </Button>
          </div>
        }
      />

      {error && (
        <Notice tone="danger" title="操作出错">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="提示">
          {notice}
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="逾期未提交"
          value={entries?.overdueCount ?? 0}
          polarity="negative"
          icon={<TriangleAlert className="size-4" />}
        />
        <StatCard
          label="临近截止未提交"
          value={entries?.dueSoonCount ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="未提交总数"
          value={entries?.notSubmittedCount ?? 0}
          polarity="neutral"
          icon={<CalendarClock className="size-4" />}
        />
        <StatCard
          label="未确认提醒"
          value={reminders?.unacknowledgedCount ?? 0}
          polarity="negative"
          icon={<BellRing className="size-4" />}
        />
      </div>

      {caps && (
        <SectionCard
          title="能力声明"
          description="上游真读得到吗、下游归谁、判定口径是什么 —— 界面上不会拿编造的数据去填那些维度。"
        >
          <ul className="space-y-2">
            {caps.upstreams.map((upstream) => (
              <li key={upstream.moduleId} className="text-copy-13 flex items-start gap-2">
                <Badge tone={upstream.available ? 'success' : 'danger'}>
                  {upstream.available ? '读得到' : '读不到'}
                </Badge>
                <span className="text-foreground">{upstream.moduleName}</span>
                <span className="text-muted-foreground">{upstream.impact}</span>
              </li>
            ))}
          </ul>
          <ul className="mt-3 space-y-2">
            {caps.gaps.map((gap) => (
              <li key={gap.moduleId} className="text-copy-13 flex items-start gap-2">
                <Badge tone={gap.available ? 'success' : 'warning'}>{gap.moduleId}</Badge>
                <span className="text-foreground">{gap.moduleName}</span>
                <span className="text-muted-foreground">{gap.impact}</span>
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground mt-3 text-copy-12">{caps.scopeNote}</p>
        </SectionCard>
      )}

      {/* ---------- 申报日历（本模块的主视图） ---------- */}
      <SectionCard
        title="申报日历"
        description="逾期与临近截止的排在最前。数据就绪是 12.6.1.2 生效底稿的投影；截止日按当地时区显示。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={jurisdictionCode}
              onChange={(e) => applyFilters({ jurisdictionCode: e.target.value })}
            >
              <option value="">全部辖区</option>
              {(options?.jurisdictions ?? []).map((item) => (
                <option key={item.code} value={item.code}>
                  {item.name}（{item.code}）
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={taxType}
              onChange={(e) => applyFilters({ taxType: e.target.value })}
            >
              {TAX_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={filingCycle}
              onChange={(e) => applyFilters({ filingCycle: e.target.value })}
            >
              {FILING_CYCLES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => applyFilters({ status: e.target.value })}
            >
              {STATUSES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {(entries?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<CalendarClock className="size-8 text-muted-foreground" />}
            title={emptyKind === 'NO_CONFIG' ? '还没有申报周期配置' : '没有申报义务'}
            description={emptyStateText(emptyKind)}
            action={
              <Button variant="secondary" onClick={openConfigCreate}>
                <Plus className="mr-2 size-4" />
                登记申报周期配置
              </Button>
            }
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground border-b">
                  <tr>
                    <th className="py-2 pr-3 font-medium">辖区</th>
                    <th className="py-2 pr-3 font-medium">申报线 / 周期</th>
                    <th className="py-2 pr-3 font-medium">申报期</th>
                    <th className="py-2 pr-3 font-medium">截止日（当地）</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 pr-3 font-medium">数据就绪</th>
                    <th className="py-2 pr-3 font-medium">责任人</th>
                    <th className="py-2 pr-3 font-medium">紧迫度</th>
                    <th className="py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {entries?.items.map((item) => {
                    const urgency = urgencyOf(item);
                    return (
                      <tr key={item.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">
                          <div className="text-foreground">{item.jurisdictionName || '—'}</div>
                          <div className="text-muted-foreground text-copy-12">
                            {item.jurisdictionCode}
                          </div>
                        </td>
                        <td className="py-2 pr-3">
                          <div>{taxTypeLabel(item.taxType)}</div>
                          <div className="text-muted-foreground text-copy-12">
                            {filingCycleLabel(item.filingCycle)}
                          </div>
                        </td>
                        <td className="py-2 pr-3">
                          {item.periodStart} ~ {item.periodEnd}
                        </td>
                        <td className="py-2 pr-3">
                          {deadlineText(item.dueAt, item.dueTimezone)}
                        </td>
                        <td className="py-2 pr-3">
                          <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                        </td>
                        <td className="py-2 pr-3 max-w-72">
                          <div className="flex items-center gap-2">
                            <Badge tone={item.dataReady ? 'success' : 'neutral'}>
                              {item.dataReady ? '就绪' : item.dataReadinessAvailable ? '未就绪' : '读不到'}
                            </Badge>
                          </div>
                          <div className="text-muted-foreground mt-1 text-copy-12">
                            {readinessText(item)}
                          </div>
                        </td>
                        <td className="py-2 pr-3">{responsibleText(item)}</td>
                        <td className="py-2 pr-3">
                          <Badge tone={urgencyTone(urgency)}>{urgencyLabel(urgency)}</Badge>
                          {item.daysToDue !== null && (
                            <div className="text-muted-foreground mt-1 text-copy-12">
                              {item.daysToDue >= 0
                                ? `还有 ${item.daysToDue} 天`
                                : `已过 ${Math.abs(item.daysToDue)} 天`}
                            </div>
                          )}
                        </td>
                        <td className="py-2">
                          <div className="flex flex-wrap items-center gap-1">
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => openEntryEdit(item)}
                            >
                              编辑
                            </Button>
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={busy || !canAdvance(item.status, 'SUBMITTED')}
                              onClick={() => {
                                setAdvanceTarget(item);
                                setAdvanceStatus('SUBMITTED');
                                setAdvanceReference('');
                              }}
                            >
                              标记已提交
                            </Button>
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={busy || !canAdvance(item.status, 'CONFIRMED')}
                              onClick={() => {
                                setAdvanceTarget(item);
                                setAdvanceStatus('CONFIRMED');
                                setAdvanceReference('');
                              }}
                            >
                              标记已确认
                            </Button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground mt-3 flex flex-wrap items-center justify-between gap-2 text-copy-12">
              <span>{entries?.notice}</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={entryPage <= 0}
                  onClick={() => setEntryPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span>
                  第 {(entries?.page ?? 0) + 1} 页 / 共 {entries?.total ?? 0} 条
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(entries?.page ?? 0) * PAGE_SIZE + PAGE_SIZE >= (entries?.total ?? 0)}
                  onClick={() => setEntryPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---------- 到期提醒 ---------- */}
      <SectionCard
        title="到期提醒"
        description="提醒是**派生的**（提前提醒 + 截止提醒两档）；确认已知悉会落库。分页截断时下面写明「只取回前 N 条（共 M 条）」。"
      >
        {(reminders?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<BellRing className="size-8 text-muted-foreground" />}
            title="没有到期提醒"
            description="日历上还没有临近截止的申报义务；登记申报义务后会自动派生提醒。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground border-b">
                  <tr>
                    <th className="py-2 pr-3 font-medium">提醒</th>
                    <th className="py-2 pr-3 font-medium">辖区</th>
                    <th className="py-2 pr-3 font-medium">申报期</th>
                    <th className="py-2 pr-3 font-medium">截止日（当地）</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 pr-3 font-medium">责任人</th>
                    <th className="py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {reminders?.items.map((item: FilingCalendarReminderView) => (
                    <tr key={item.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <Badge tone={item.acknowledged ? 'neutral' : 'warning'}>
                          {reminderKindLabel(item.kind)}
                        </Badge>
                        <div className="text-muted-foreground mt-1 text-copy-12">
                          提醒于 {item.remindAt.replace('T', ' ').slice(0, 16)}
                        </div>
                      </td>
                      <td className="py-2 pr-3">
                        {item.jurisdictionName || item.jurisdictionCode}
                      </td>
                      <td className="py-2 pr-3">
                        {item.periodStart} ~ {item.periodEnd}
                      </td>
                      <td className="py-2 pr-3">{deadlineText(item.dueAt, item.dueTimezone)}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                      </td>
                      <td className="py-2 pr-3">{responsibleText(item)}</td>
                      <td className="py-2">
                        {item.acknowledged ? (
                          <span className="text-muted-foreground text-copy-12">
                            {item.acknowledgedAt.replace('T', ' ').slice(0, 16)} 已确认
                          </span>
                        ) : (
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={busy}
                            onClick={() => doAcknowledge(item.id)}
                          >
                            确认已知悉
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground mt-3 flex flex-wrap items-center justify-between gap-2 text-copy-12">
              <span>{reminders?.notice}</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={reminderPage <= 0}
                  onClick={() => setReminderPage((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span>
                  第 {(reminders?.page ?? 0) + 1} 页 / 共 {reminders?.total ?? 0} 条
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(reminders?.page ?? 0) * PAGE_SIZE + PAGE_SIZE >= (reminders?.total ?? 0)}
                  onClick={() => setReminderPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      {/* ---------- 申报周期配置 ---------- */}
      <SectionCard
        title="申报周期配置"
        description="**模板**：怎么算期、怎么算截止日、提前几天提醒。真正的截止日会快照进日历条目 —— 改配置不改历史条目。"
        extra={
          <Button variant="outline" size="sm" onClick={openConfigCreate}>
            <Plus className="mr-2 size-4" />
            登记配置
          </Button>
        }
      >
        {(configs?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ScrollText className="size-8 text-muted-foreground" />}
            title="还没有申报周期配置"
            description="日历上还没有申报期，是因为还没有「怎么算」的模板。登记一份（辖区 × 申报线 × 周期）就能按配置生成。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="py-2 pr-3 font-medium">辖区</th>
                  <th className="py-2 pr-3 font-medium">申报线 / 周期</th>
                  <th className="py-2 pr-3 font-medium">期起始日</th>
                  <th className="py-2 pr-3 font-medium">截止规则</th>
                  <th className="py-2 pr-3 font-medium">提前提醒</th>
                  <th className="py-2 pr-3 font-medium">生效起始日</th>
                  <th className="py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {configs?.items.map((item) => (
                  <tr key={item.id} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      {item.jurisdictionName || item.jurisdictionCode}
                    </td>
                    <td className="py-2 pr-3">
                      {taxTypeLabel(item.taxType)} / {filingCycleLabel(item.filingCycle)}
                    </td>
                    <td className="py-2 pr-3">每月 {item.periodAnchorDay} 号</td>
                    <td className="py-2 pr-3">
                      期末 + {item.dueOffsetDays} 天 {String(item.dueHour).padStart(2, '0')}:00（
                      {item.dueTimezone}）
                    </td>
                    <td className="py-2 pr-3">{item.reminderLeadDays} 天</td>
                    <td className="py-2 pr-3">{item.effectiveFrom}</td>
                    <td className="py-2">
                      <Button variant="outline" size="sm" onClick={() => openConfigEdit(item.id)}>
                        改配置
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---------- 申报周期配置表单 ---------- */}
      <Modal open={configOpen} onOpenChange={setConfigOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{configEditing ? '改申报周期配置' : '登记申报周期配置'}</ModalTitle>
            <ModalDescription>
              截止日 = 申报期末 + 偏移天数，在**配置的当地时刻**触发 —— 写死 UTC 会差几小时，
              而差几小时就足以错过一个截止日。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField id="fc-jurisdiction" label="辖区" required>
              <select
                id="fc-jurisdiction"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={configForm.jurisdictionCode}
                onChange={(e) =>
                  setConfigForm({ ...configForm, jurisdictionCode: e.target.value })
                }
              >
                <option value="">请选择辖区</option>
                {(options?.jurisdictions ?? []).map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.name}（{item.code}）
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fc-tax-type" label="申报线（税种）" required>
              <select
                id="fc-tax-type"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={configForm.taxType}
                onChange={(e) => setConfigForm({ ...configForm, taxType: e.target.value })}
              >
                {TAX_TYPES.filter((item) => item.value).map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fc-cycle" label="申报周期" required>
              <select
                id="fc-cycle"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={configForm.filingCycle}
                onChange={(e) => setConfigForm({ ...configForm, filingCycle: e.target.value })}
              >
                {FILING_CYCLES.filter((item) => item.value).map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="fc-anchor"
              label="期起始日（几号，1-28）"
              hint="刻意不超过 28 —— 短月没有 29/30/31 号，配置就会在某些月份算不出期。"
              required
            >
              <Input
                id="fc-anchor"
                type="number"
                min={1}
                max={28}
                value={configForm.periodAnchorDay}
                onChange={(e) => setConfigForm({ ...configForm, periodAnchorDay: e.target.value })}
              />
            </FormField>
            <FormField id="fc-offset" label="截止偏移（期末 + N 天）" required>
              <Input
                id="fc-offset"
                type="number"
                min={0}
                max={120}
                value={configForm.dueOffsetDays}
                onChange={(e) => setConfigForm({ ...configForm, dueOffsetDays: e.target.value })}
              />
            </FormField>
            <FormField id="fc-hour" label="截止时刻（当地小时 0-23）" required>
              <Input
                id="fc-hour"
                type="number"
                min={0}
                max={23}
                value={configForm.dueHour}
                onChange={(e) => setConfigForm({ ...configForm, dueHour: e.target.value })}
              />
            </FormField>
            <FormField
              id="fc-zone"
              label="截止时区（IANA 名）"
              hint="如 Europe/Berlin / UTC / Asia/Shanghai"
              required
            >
              <Input
                id="fc-zone"
                value={configForm.dueTimezone}
                onChange={(e) => setConfigForm({ ...configForm, dueTimezone: e.target.value })}
              />
            </FormField>
            <FormField id="fc-lead" label="提前提醒天数" required>
              <Input
                id="fc-lead"
                type="number"
                min={0}
                max={180}
                value={configForm.reminderLeadDays}
                onChange={(e) => setConfigForm({ ...configForm, reminderLeadDays: e.target.value })}
              />
            </FormField>
            <FormField
              id="fc-from"
              label="生效起始日"
              hint="留空 = 今天。申报期以这一天为原点按周期排格子。"
            >
              <Input
                id="fc-from"
                type="date"
                value={configForm.effectiveFrom}
                onChange={(e) => setConfigForm({ ...configForm, effectiveFrom: e.target.value })}
              />
            </FormField>
            <FormField id="fc-note" label="备注">
              <Input
                id="fc-note"
                value={configForm.note}
                onChange={(e) => setConfigForm({ ...configForm, note: e.target.value })}
              />
            </FormField>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setConfigOpen(false)}>
              取消
            </Button>
            <Button onClick={submitConfig} disabled={busy}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 申报义务表单 ---------- */}
      <Modal open={entryOpen} onOpenChange={setEntryOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{entryEditing ? '改申报义务' : '登记申报义务'}</ModalTitle>
            <ModalDescription>
              截止日留空 = 按周期配置推算。责任人必须是**租户内的成员**（落库前会校验）。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField id="fe-jurisdiction" label="辖区" required>
              <select
                id="fe-jurisdiction"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={entryForm.jurisdictionCode}
                onChange={(e) =>
                  setEntryForm({ ...entryForm, jurisdictionCode: e.target.value })
                }
              >
                <option value="">请选择辖区</option>
                {(options?.jurisdictions ?? []).map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.name}（{item.code}）
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fe-tax-type" label="申报线（税种）" required>
              <select
                id="fe-tax-type"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={entryForm.taxType}
                onChange={(e) => setEntryForm({ ...entryForm, taxType: e.target.value })}
              >
                {TAX_TYPES.filter((item) => item.value).map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fe-cycle" label="申报周期" required>
              <select
                id="fe-cycle"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={entryForm.filingCycle}
                onChange={(e) => setEntryForm({ ...entryForm, filingCycle: e.target.value })}
              >
                {FILING_CYCLES.filter((item) => item.value).map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fe-start" label="申报期起" required>
              <Input
                id="fe-start"
                type="date"
                value={entryForm.periodStart}
                onChange={(e) => setEntryForm({ ...entryForm, periodStart: e.target.value })}
              />
            </FormField>
            <FormField id="fe-end" label="申报期止" required>
              <Input
                id="fe-end"
                type="date"
                value={entryForm.periodEnd}
                onChange={(e) => setEntryForm({ ...entryForm, periodEnd: e.target.value })}
              />
            </FormField>
            <FormField id="fe-due" label="截止日" hint="留空 = 按周期配置推算（当地时刻）">
              <Input
                id="fe-due"
                placeholder="2026-07-10T23:00:00+02:00"
                value={entryForm.dueAt}
                onChange={(e) => setEntryForm({ ...entryForm, dueAt: e.target.value })}
              />
            </FormField>
            <FormField
              id="fe-owner"
              label="责任人"
              hint={
                options?.members.available
                  ? '租户内的成员（identity 拥有，本模块只存逻辑 ID）'
                  : `成员目录读不到：${options?.members.note ?? ''}`
              }
            >
              <select
                id="fe-owner"
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={entryForm.responsibleMembershipId}
                onChange={(e) =>
                  setEntryForm({ ...entryForm, responsibleMembershipId: e.target.value })
                }
              >
                <option value="">未指定</option>
                {(options?.members.items ?? []).map((item) => (
                  <option key={item.membershipId} value={item.membershipId}>
                    {item.username || item.membershipId}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="fe-lead" label="提前提醒天数" hint="留空 = 按周期配置取">
              <Input
                id="fe-lead"
                type="number"
                min={0}
                max={180}
                value={entryForm.reminderLeadDays}
                onChange={(e) => setEntryForm({ ...entryForm, reminderLeadDays: e.target.value })}
              />
            </FormField>
            <FormField
              id="fe-required"
              label="所需数据（每行一条：编码|名称|必需/可选|来源）"
              hint="这次申报要凑齐哪些数据 —— 是这个条目自己的口径，不是全局常量。"
            >
              <Input
                id="fe-required"
                value={entryForm.requiredDataText}
                onChange={(e) =>
                  setEntryForm({ ...entryForm, requiredDataText: e.target.value })
                }
              />
            </FormField>
            <FormField id="fe-note" label="备注">
              <Input
                id="fe-note"
                value={entryForm.note}
                onChange={(e) => setEntryForm({ ...entryForm, note: e.target.value })}
              />
            </FormField>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setEntryOpen(false)}>
              取消
            </Button>
            <Button onClick={submitEntry} disabled={busy}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 推进状态（已提交 / 已确认必须带凭据号） ---------- */}
      <Modal open={advanceTarget !== null} onOpenChange={(open) => !open && setAdvanceTarget(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              标记为「{advanceStatus === 'SUBMITTED' ? '已提交' : '已确认'}」
            </ModalTitle>
            <ModalDescription>
              🔴 **必须填凭据号**：系统**不代造受理号**，凭据号是你从税务机构那边拿到的外部凭据。
              没有凭据号的「已提交」是假的。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div className="text-copy-13 text-muted-foreground">
              {advanceTarget?.jurisdictionName || advanceTarget?.jurisdictionCode} ·{' '}
              {advanceTarget?.periodStart} ~ {advanceTarget?.periodEnd} · 截止{' '}
              {advanceTarget ? deadlineText(advanceTarget.dueAt, advanceTarget.dueTimezone) : ''}
            </div>
            <FormField id="fa-reference" label="凭据号" required>
              <Input
                id="fa-reference"
                placeholder="例如 DE-2026-03-4711"
                value={advanceReference}
                onChange={(e) => setAdvanceReference(e.target.value)}
              />
            </FormField>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setAdvanceTarget(null)}>
              取消
            </Button>
            <Button onClick={submitAdvance} disabled={busy}>
              确认推进
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
