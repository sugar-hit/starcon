'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented, StatCard } from '@starcon/ui';
import { AlertTriangle, BarChart3, BellRing, RefreshCw, Send, ShieldOff } from 'lucide-react';
import type {
  CrmCareReminderCapabilities,
  CrmCareReminderRulePage,
  CrmCareReminderStatEventPage,
  CrmCareReminderStats,
  CrmCareReminderTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  careReminderBlockText,
  careReminderEventLabel,
  careReminderPaymentDemandAllowed,
  careReminderPaymentDemandHint,
  careReminderStatsText,
  careReminderStatusLabel,
  careReminderTransportLabel,
  careReminderTypeLabel,
  createCareReminderRule,
  dispatchCareReminderTask,
  fetchCareReminderCapabilities,
  fetchCareReminderPlatformReceiptStatus,
  fetchCareReminderRules,
  fetchCareReminderStats,
  fetchCareReminderTaskEvents,
  fetchCareReminderTasks,
  generateCareReminderTasks,
  recordCareReminderStatEvent,
  setCareReminderRuleStatus,
} from './care-reminder-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.5.2.1 关怀与催办工作区。
 *
 * <p>页面按「先看清边界、再配规则、然后才动手」的顺序排，因为蓝图加粗句是
 * 「**遵守频次上限与退订要求**」与「**催付有合规边界**：部分平台限制催付话术与频次，
 * 规则需按平台配置，过度催付会被投诉」。
 * 界面上第一件要让人看到的就是**能力边界**与**催付白名单**，
 * 而不是一张可以直接点「群发」的按钮。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台投递、平台打开/点击回执、12 财务、15.5.1.1 自动回复四块 available=false，
 *       页面把它们<b>显式灰掉</b>并写清归属模块。</li>
 *   <li><b>触达规则</b> —— 真实工作区：类型 / 平台 / 频次上限 / 频次窗口 / 静默时段 /
 *       定时发送。催付的平台不在白名单时，页面**提前**提示为什么配不了。</li>
 *   <li><b>触达任务</b> —— 真分页真筛选；被拦截的触达**逐条显示后端给的原因原文**
 *       （退订 / 频次超限），不只写「已拦截」。</li>
 *   <li><b>发送结果</b> —— 平台侧发送接口未接入 ⇒ 只登记人工交接结果，
 *       失败必须写原因。</li>
 *   <li><b>统计</b> —— 触达总数 / 成功 / 失败 / 打开 / 点击 / 两类拦截数<b>全部由后端算</b>，
 *       页面<b>不做任何算术</b>。</li>
 * </ul>
 */
export function CareReminderWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.care_reminder.update');

  const [view, setView] = useState<'rules' | 'tasks' | 'stats'>('rules');

  const [capabilities, setCapabilities] = useState<CrmCareReminderCapabilities | null>(null);
  const [rules, setRules] = useState<CrmCareReminderRulePage | null>(null);
  const [tasks, setTasks] = useState<CrmCareReminderTaskPage | null>(null);
  const [stats, setStats] = useState<CrmCareReminderStats | null>(null);
  const [events, setEvents] = useState<CrmCareReminderStatEventPage | null>(null);
  const [platformReceipt, setPlatformReceipt] = useState<string | null>(null);

  // 规则筛选
  const [ruleType, setRuleType] = useState('');
  const [rulePlatform, setRulePlatform] = useState('');
  const [ruleStatus, setRuleStatus] = useState('');
  const [ruleKeyword, setRuleKeyword] = useState('');
  const [rulePage, setRulePage] = useState(0);

  // 任务筛选
  const [taskType, setTaskType] = useState('');
  const [taskStatus, setTaskStatus] = useState('');
  const [taskBlock, setTaskBlock] = useState('');
  const [taskCustomer, setTaskCustomer] = useState('');
  const [taskPage, setTaskPage] = useState(0);

  // 新建规则
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [newType, setNewType] = useState('SHIPMENT_NOTICE');
  const [newPlatform, setNewPlatform] = useState('SHOPIFY');
  const [frequencyLimit, setFrequencyLimit] = useState('1');
  const [frequencyWindow, setFrequencyWindow] = useState('24');
  const [quietStart, setQuietStart] = useState('');
  const [quietEnd, setQuietEnd] = useState('');
  const [sendTime, setSendTime] = useState('09:00');
  const [timezone, setTimezone] = useState('UTC');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');

  // 生成任务
  const [generateRuleId, setGenerateRuleId] = useState('');
  const [lookbackDays, setLookbackDays] = useState('');

  // 人工交接登记
  const [dispatchTaskId, setDispatchTaskId] = useState('');
  const [dispatchOutcome, setDispatchOutcome] = useState('SENT');
  const [dispatchFailure, setDispatchFailure] = useState('');
  const [dispatchReceipt, setDispatchReceipt] = useState('');

  // 统计事件
  const [eventTaskId, setEventTaskId] = useState('');
  const [eventType, setEventType] = useState('OPEN');
  const [eventSource, setEventSource] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ruleResult, taskResult, statResult] = await Promise.all([
        fetchCareReminderCapabilities(),
        fetchCareReminderRules({
          reminderType: ruleType || undefined,
          platformCode: rulePlatform || undefined,
          status: ruleStatus || undefined,
          keyword: ruleKeyword || undefined,
          page: rulePage,
          size: 20,
        }),
        fetchCareReminderTasks({
          reminderType: taskType || undefined,
          status: taskStatus || undefined,
          blockCode: taskBlock || undefined,
          customerRef: taskCustomer || undefined,
          page: taskPage,
          size: 20,
        }),
        fetchCareReminderStats({
          reminderType: taskType || undefined,
        }),
      ]);
      setCapabilities(caps);
      setRules(ruleResult);
      setTasks(taskResult);
      setStats(statResult);
    } catch (e) {
      setError(errorText(e, '关怀与催办数据读取失败'));
    } finally {
      setLoading(false);
    }
  }, [
    ruleType,
    rulePlatform,
    ruleStatus,
    ruleKeyword,
    rulePage,
    taskType,
    taskStatus,
    taskBlock,
    taskCustomer,
    taskPage,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const allowedPaymentPlatforms = useMemo(
    () => capabilities?.paymentDemandAllowedPlatforms ?? [],
    [capabilities],
  );
  const paymentDemandAllowed = careReminderPaymentDemandAllowed(
    newPlatform,
    allowedPaymentPlatforms,
  );
  const availableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => d.available) ?? [],
    [capabilities],
  );
  const unavailableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => !d.available) ?? [],
    [capabilities],
  );
  const hasRuleFilter = Boolean(ruleType || rulePlatform || ruleStatus || ruleKeyword);
  const hasTaskFilter = Boolean(taskType || taskStatus || taskBlock || taskCustomer);

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createCareReminderRule({
        ruleCode: ruleCode.trim(),
        ruleName: ruleName.trim(),
        reminderType: newType,
        platformCode: newPlatform.trim().toUpperCase(),
        shopId: null,
        shopName: null,
        targetFilter: lookbackDays.trim() ? { lookbackDays: Number(lookbackDays) } : {},
        frequencyLimit: Number(frequencyLimit),
        frequencyWindowHours: Number(frequencyWindow),
        quietHoursStart: quietStart.trim() || null,
        quietHoursEnd: quietEnd.trim() || null,
        sendTime: sendTime.trim() || null,
        timezone: timezone.trim() || null,
        templateCode: null,
        subject: subject.trim(),
        body: body.trim(),
        note: null,
      });
      setNotice(
        `已创建触达规则 ${created.ruleCode}（${created.reminderTypeLabel} · ${created.platformCode}）：` +
          `频次上限 ${created.frequencyLimit} 次 / ${created.frequencyWindowHours} 小时。` +
          (created.quietHoursStart
            ? `静默时段 ${created.quietHoursStart} - ${created.quietHoursEnd}。`
            : '未设静默时段。'),
      );
      setRuleCode('');
      setRuleName('');
      setSubject('');
      setBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '触达规则创建失败'));
    } finally {
      setBusy(false);
    }
  }, [
    ruleCode,
    ruleName,
    newType,
    newPlatform,
    lookbackDays,
    frequencyLimit,
    frequencyWindow,
    quietStart,
    quietEnd,
    sendTime,
    timezone,
    subject,
    body,
    load,
  ]);

  const toggleRule = useCallback(
    async (ruleId: string, currentStatus: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const next = currentStatus === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
        await setCareReminderRuleStatus(ruleId, next);
        setNotice(
          next === 'DISABLED'
            ? '规则已停用：不能再生成新的触达任务，历史任务仍可读。'
            : '规则已启用。',
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '规则状态切换失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const runGenerate = useCallback(async () => {
    if (!generateRuleId) {
      setActionError('请先选择要按哪条规则生成触达任务。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await generateCareReminderTasks(generateRuleId, {
        plannedAt: null,
        lookbackDays: lookbackDays.trim() ? Number(lookbackDays) : null,
        minAmount: null,
        orderSource: null,
        customerRefs: null,
      });
      setNotice(
        `已排期 ${result.created} 条，被拦截 ${result.blocked} 条` +
          `（幂等命中 ${result.idempotentHits} 条，当天已生成过的不重复记账）。${result.note}`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '生成触达任务失败'));
    } finally {
      setBusy(false);
    }
  }, [generateRuleId, lookbackDays, load]);

  const submitDispatch = useCallback(async () => {
    if (!dispatchTaskId) {
      setActionError('请先选择要登记发送结果的那条触达。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const updated = await dispatchCareReminderTask(dispatchTaskId, {
        outcome: dispatchOutcome,
        sentAt: null,
        failureReason: dispatchFailure.trim() || null,
        platformReceipt: dispatchReceipt.trim() || null,
        note: null,
      });
      setNotice(
        `已登记 ${updated.taskNo} 的发送结果：${updated.statusLabel}` +
          (updated.failureReason ? `（失败原因：${updated.failureReason}）` : '') +
          '。平台侧发送接口未接入，发送本身由人工在平台侧完成。',
      );
      setDispatchFailure('');
      setDispatchReceipt('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '发送结果登记失败'));
    } finally {
      setBusy(false);
    }
  }, [dispatchTaskId, dispatchOutcome, dispatchFailure, dispatchReceipt, load]);

  const loadEvents = useCallback(async () => {
    if (!eventTaskId) {
      setActionError('请先选择要看统计的那条触达。');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const page = await fetchCareReminderTaskEvents(eventTaskId, { size: 50 });
      setEvents(page);
    } catch (e) {
      setActionError(errorText(e, '打开/点击统计读取失败'));
    } finally {
      setBusy(false);
    }
  }, [eventTaskId]);

  const loadPlatformReceipt = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const status = await fetchCareReminderPlatformReceiptStatus();
      setPlatformReceipt(status.reason);
    } catch (e) {
      setActionError(errorText(e, '平台回执接入状态读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitEvent = useCallback(async () => {
    if (!eventTaskId) {
      setActionError('请先选择要录入统计的那条触达。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await recordCareReminderStatEvent({
        taskId: eventTaskId,
        eventType,
        occurredAt: null,
        source: eventSource.trim() || null,
      });
      setNotice(
        `已录入一条${careReminderEventLabel(eventType)}事件。` +
          '⚠️ 平台打开/点击回执**未接入** —— 自动采集端点如实答「未接入」且不写数据，' +
          '统计只能靠平台 webhook 或人工录入。',
      );
      setEventSource('');
      const page = await fetchCareReminderTaskEvents(eventTaskId, { size: 50 });
      setEvents(page);
      await load();
    } catch (e) {
      setActionError(errorText(e, '统计事件录入失败'));
    } finally {
      setBusy(false);
    }
  }, [eventTaskId, eventType, eventSource, load]);

  if (loading && !rules) {
    return (
      <SectionCard title="关怀与催办">
        <p className="text-muted-foreground text-copy-13">正在读取触达规则与触达任务……</p>
      </SectionCard>
    );
  }

  if (error && !rules) {
    return (
      <SectionCard title="关怀与催办">
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
        <div className="mt-3">
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            重试
          </Button>
        </div>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {/* ---------------------------------------------------------- 能力边界 */}
      <SectionCard title="能力边界">
        <p className="text-muted-foreground text-copy-13">{capabilities?.highlight}</p>
        <ul className="mt-3 space-y-2">
          {availableDimensions.map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone="success">可用</Badge>
              <span className="font-medium">{dimension.description}</span>
              <span className="text-muted-foreground">—— {dimension.source}</span>
            </li>
          ))}
          {unavailableDimensions.map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone="warning">未接入</Badge>
              <span className="font-medium">{dimension.description}</span>
              <span className="text-muted-foreground">
                —— {dimension.source}
                {dimension.ownerModule ? `（归 ${dimension.ownerModule} 模块）` : ''}
              </span>
              <span className="text-muted-foreground block w-full text-xs">{dimension.impact}</span>
            </li>
          ))}
        </ul>
        <div className="border-border bg-muted/40 mt-3 rounded-md px-3 py-2">
          <p className="text-copy-13 font-medium">催付合规边界</p>
          <p className="text-muted-foreground text-copy-13 mt-1">
            {careReminderPaymentDemandHint(allowedPaymentPlatforms)}
          </p>
          <p className="text-muted-foreground text-copy-13 mt-1">{capabilities?.boundaryNote}</p>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 视图切换 */}
      <div className="flex flex-wrap items-center gap-2">
        <Segmented
          value={view}
          onChange={setView}
          aria-label="关怀与催办工作区视图"
          options={[
            { value: 'rules', label: '触达规则', icon: <BellRing className="h-4 w-4" /> },
            { value: 'tasks', label: '触达任务', icon: <Send className="h-4 w-4" /> },
            { value: 'stats', label: '统计与打开/点击', icon: <BarChart3 className="h-4 w-4" /> },
          ]}
        />
        <Button onClick={() => void load()} size="sm" variant="secondary" disabled={loading}>
          <RefreshCw className="mr-1 h-4 w-4" />
          刷新
        </Button>
      </div>

      {notice && (
        <div className="border-success/40 bg-success/10 text-success text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {actionError}
        </div>
      )}

      {/* ---------------------------------------------------------- 触达规则 */}
      {view === 'rules' && (
        <>
          <SectionCard title="触达规则">
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="类型">
                <select
                  className={control}
                  value={ruleType}
                  onChange={(e) => setRuleType(e.target.value)}
                >
                  <option value="">全部</option>
                  {capabilities?.reminderTypes.map((type) => (
                    <option key={type} value={type}>
                      {careReminderTypeLabel(type)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="平台">
                <input
                  className={control}
                  value={rulePlatform}
                  onChange={(e) => setRulePlatform(e.target.value)}
                  placeholder="例如 SHOPIFY"
                />
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={ruleStatus}
                  onChange={(e) => setRuleStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  <option value="ACTIVE">启用</option>
                  <option value="DISABLED">停用</option>
                </select>
              </Field>
              <Field label="关键字">
                <input
                  className={control}
                  value={ruleKeyword}
                  onChange={(e) => setRuleKeyword(e.target.value)}
                  placeholder="规则编码 / 名称"
                />
              </Field>
            </div>

            {!rules || rules.items.length === 0 ? (
              <div className="mt-4">
                {hasRuleFilter ? (
                  <EmptyState
                    icon={<BellRing className="h-6 w-6" />}
                    title="当前筛选没有匹配的触达规则"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有触达规则」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<BellRing className="h-6 w-6" />}
                    title="还没有触达规则"
                    description="先在下面配一条规则（类型 / 平台 / 频次上限 / 静默时段），再按规则生成触达任务。"
                  />
                )}
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">规则</th>
                    <th className="py-2">类型 / 平台</th>
                    <th className="py-2">频次上限</th>
                    <th className="py-2">静默时段 / 定时</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rules.items.map((rule) => (
                    <tr key={rule.id} className="border-border border-t">
                      <td className="py-2">
                        <div className="font-medium">{rule.ruleName}</div>
                        <div className="text-muted-foreground">{rule.ruleCode}</div>
                      </td>
                      <td className="py-2">
                        <div>{rule.reminderTypeLabel}</div>
                        <div className="text-muted-foreground">{rule.platformCode}</div>
                      </td>
                      <td className="py-2">
                        {rule.frequencyLimit} 次 / {rule.frequencyWindowHours} 小时
                      </td>
                      <td className="py-2">
                        <div>
                          {rule.quietHoursStart
                            ? `${rule.quietHoursStart} - ${rule.quietHoursEnd}`
                            : '未设静默时段'}
                        </div>
                        <div className="text-muted-foreground">
                          每日 {rule.sendTime}（{rule.timezone}）
                        </div>
                      </td>
                      <td className="py-2">
                        <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {rule.statusLabel}
                        </Badge>
                      </td>
                      <td className="py-2 text-right">
                        {writable && (
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void toggleRule(rule.id, rule.status)}
                          >
                            {rule.status === 'ACTIVE' ? '停用' : '启用'}
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {rules && rules.totalPages > 1 && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={rulePage <= 0}
                  onClick={() => setRulePage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-copy-13">
                  第 {rules.page + 1} / {rules.totalPages} 页（合计 {rules.totalElements} 条）
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={rulePage + 1 >= rules.totalPages}
                  onClick={() => setRulePage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {writable && (
            <SectionCard title="新建触达规则">
              <div className="grid gap-3 md:grid-cols-3">
                <Field label="规则编码">
                  <input
                    className={control}
                    value={ruleCode}
                    onChange={(e) => setRuleCode(e.target.value)}
                    placeholder="例如 R-SHIP-01"
                  />
                </Field>
                <Field label="规则名称">
                  <input
                    className={control}
                    value={ruleName}
                    onChange={(e) => setRuleName(e.target.value)}
                    placeholder="例如 发货通知（Shopify）"
                  />
                </Field>
                <Field label="触达类型">
                  <select
                    className={control}
                    value={newType}
                    onChange={(e) => setNewType(e.target.value)}
                  >
                    {capabilities?.reminderTypes.map((type) => (
                      <option key={type} value={type}>
                        {careReminderTypeLabel(type)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label="适用平台"
                  hint={
                    newType === 'PAYMENT_DEMAND' && !paymentDemandAllowed
                      ? careReminderPaymentDemandHint(allowedPaymentPlatforms)
                      : '一个规则只对一个平台生效：不同平台的催付话术与频次上限可以各配各的。'
                  }
                >
                  <input
                    className={control}
                    value={newPlatform}
                    onChange={(e) => setNewPlatform(e.target.value)}
                    placeholder="例如 SHOPIFY"
                  />
                </Field>
                <Field label="频次上限（窗口内最多触达几次）">
                  <input
                    className={control}
                    value={frequencyLimit}
                    onChange={(e) => setFrequencyLimit(e.target.value)}
                    inputMode="numeric"
                  />
                </Field>
                <Field label="频次窗口（小时）">
                  <input
                    className={control}
                    value={frequencyWindow}
                    onChange={(e) => setFrequencyWindow(e.target.value)}
                    inputMode="numeric"
                  />
                </Field>
                <Field label="静默时段起（HH:mm，可空）">
                  <input
                    className={control}
                    value={quietStart}
                    onChange={(e) => setQuietStart(e.target.value)}
                    placeholder="22:00"
                  />
                </Field>
                <Field label="静默时段止（HH:mm，可空）">
                  <input
                    className={control}
                    value={quietEnd}
                    onChange={(e) => setQuietEnd(e.target.value)}
                    placeholder="08:00"
                  />
                </Field>
                <Field label="每日发送时刻（HH:mm）">
                  <input
                    className={control}
                    value={sendTime}
                    onChange={(e) => setSendTime(e.target.value)}
                    placeholder="09:00"
                  />
                </Field>
                <Field label="时区（IANA）">
                  <input
                    className={control}
                    value={timezone}
                    onChange={(e) => setTimezone(e.target.value)}
                    placeholder="Asia/Shanghai"
                  />
                </Field>
                <Field label="目标回看天数（可空）">
                  <input
                    className={control}
                    value={lookbackDays}
                    onChange={(e) => setLookbackDays(e.target.value)}
                    inputMode="numeric"
                    placeholder="30"
                  />
                </Field>
                <Field label="话术标题">
                  <input
                    className={control}
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    placeholder="你的订单已发出"
                  />
                </Field>
                <Field label="话术正文">
                  <textarea
                    className={control}
                    rows={3}
                    value={body}
                    onChange={(e) => setBody(e.target.value)}
                    placeholder="你的订单已发出，点击查看物流。"
                  />
                </Field>
              </div>
              <div className="mt-3 flex items-center gap-2">
                <Button onClick={() => void submitRule()} disabled={busy}>
                  创建规则
                </Button>
                <span className="text-muted-foreground text-copy-13">
                  静默时段要**起止都填**或都留空；落在静默时段的触达会被**顺延**到静默结束（不是被拒）。
                </span>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 触达任务 */}
      {view === 'tasks' && (
        <>
          <SectionCard title="触达任务">
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="类型">
                <select
                  className={control}
                  value={taskType}
                  onChange={(e) => setTaskType(e.target.value)}
                >
                  <option value="">全部</option>
                  {capabilities?.reminderTypes.map((type) => (
                    <option key={type} value={type}>
                      {careReminderTypeLabel(type)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={taskStatus}
                  onChange={(e) => setTaskStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  {capabilities?.reminderStatuses.map((status) => (
                    <option key={status} value={status}>
                      {careReminderStatusLabel(status)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="拦截原因">
                <select
                  className={control}
                  value={taskBlock}
                  onChange={(e) => setTaskBlock(e.target.value)}
                >
                  <option value="">全部</option>
                  {capabilities?.blockCodes.map((code) => (
                    <option key={code} value={code}>
                      {code === 'OPT_OUT' ? '退订拦截' : '频次超限'}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="买家标识">
                <input
                  className={control}
                  value={taskCustomer}
                  onChange={(e) => setTaskCustomer(e.target.value)}
                  placeholder="客户标识"
                />
              </Field>
            </div>

            {!tasks || tasks.items.length === 0 ? (
              <div className="mt-4">
                {hasTaskFilter ? (
                  <EmptyState
                    icon={<Send className="h-6 w-6" />}
                    title="当前筛选没有匹配的触达任务"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有触达任务」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<Send className="h-6 w-6" />}
                    title="还没有触达任务"
                    description="先到「触达规则」里配一条规则，再按规则生成触达任务。"
                  />
                )}
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">任务</th>
                    <th className="py-2">类型 / 目标</th>
                    <th className="py-2">计划 / 发出</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">打开 / 点击</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.items.map((task) => (
                    <tr key={task.id} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{task.taskNo}</div>
                        <div className="text-muted-foreground">{task.ruleCode}</div>
                      </td>
                      <td className="py-2">
                        <div>{task.reminderTypeLabel}</div>
                        <div className="text-muted-foreground">{task.customerRef}</div>
                        {task.orderReference && (
                          <div className="text-muted-foreground">订单 {task.orderReference}</div>
                        )}
                      </td>
                      <td className="py-2">
                        <div>计划 {task.plannedAt}</div>
                        {task.sentAt && (
                          <div className="text-muted-foreground">发出 {task.sentAt}</div>
                        )}
                        <div className="text-muted-foreground">{task.transportLabel}</div>
                      </td>
                      <td className="py-2">
                        <Badge
                          tone={
                            task.status === 'SENT'
                              ? 'success'
                              : task.status === 'BLOCKED' || task.status === 'FAILED'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {task.statusLabel}
                        </Badge>
                        {task.status === 'BLOCKED' && task.blockCode && (
                          <div className="text-destructive mt-1 max-w-[28rem] whitespace-pre-wrap text-xs">
                            {careReminderBlockText(task.blockCode, task.blockReason)}
                          </div>
                        )}
                        {task.status === 'FAILED' && task.failureReason && (
                          <div className="text-destructive mt-1 max-w-[28rem] whitespace-pre-wrap text-xs">
                            失败原因：{task.failureReason}
                          </div>
                        )}
                        {task.note && (
                          <div className="text-muted-foreground mt-1 max-w-[28rem] whitespace-pre-wrap text-xs">
                            {task.note}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        {task.openCount} / {task.clickCount}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {tasks && tasks.totalPages > 1 && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={taskPage <= 0}
                  onClick={() => setTaskPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-copy-13">
                  第 {tasks.page + 1} / {tasks.totalPages} 页（合计 {tasks.totalElements} 条）
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={taskPage + 1 >= tasks.totalPages}
                  onClick={() => setTaskPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {writable && (
            <>
              <SectionCard title="按规则生成触达任务">
                <div className="grid gap-3 md:grid-cols-3">
                  <Field label="按哪条规则生成">
                    <select
                      className={control}
                      value={generateRuleId}
                      onChange={(e) => setGenerateRuleId(e.target.value)}
                    >
                      <option value="">请选择</option>
                      {rules?.items.map((rule) => (
                        <option key={rule.id} value={rule.id}>
                          {rule.ruleCode} · {rule.reminderTypeLabel} · {rule.platformCode}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="目标回看天数（可空）">
                    <input
                      className={control}
                      value={lookbackDays}
                      onChange={(e) => setLookbackDays(e.target.value)}
                      inputMode="numeric"
                      placeholder="沿用规则里的设置"
                    />
                  </Field>
                </div>
                <div className="mt-3 flex items-center gap-2">
                  <Button onClick={() => void runGenerate()} disabled={busy}>
                    生成触达任务
                  </Button>
                  <span className="text-muted-foreground text-copy-13">
                    每一条都要过后端的**退订闸**与**频次闸**；被挡下的会落成一条带**明确原因**的拦截记录。
                  </span>
                </div>
              </SectionCard>

              <SectionCard title="人工交接登记发送结果">
                <div className="border-border bg-muted/40 mb-3 rounded-md px-3 py-2">
                  <p className="text-copy-13 flex items-start gap-2">
                    <AlertTriangle className="text-destructive mt-0.5 h-4 w-4 shrink-0" />
                    <span>
                      平台侧发送接口**未接入** ⇒ 本模块**不重造发送**：
                      在平台侧实际发出后回这里登记「已发出 / 失败原因 / 平台回执」。
                      登记失败时**必须写原因**。
                    </span>
                  </p>
                </div>
                <div className="grid gap-3 md:grid-cols-4">
                  <Field label="哪条触达">
                    <select
                      className={control}
                      value={dispatchTaskId}
                      onChange={(e) => setDispatchTaskId(e.target.value)}
                    >
                      <option value="">请选择</option>
                      {tasks?.items
                        .filter((task) => task.status === 'QUEUED')
                        .map((task) => (
                          <option key={task.id} value={task.id}>
                            {task.taskNo} · {task.customerRef}
                          </option>
                        ))}
                    </select>
                  </Field>
                  <Field label="结果">
                    <select
                      className={control}
                      value={dispatchOutcome}
                      onChange={(e) => setDispatchOutcome(e.target.value)}
                    >
                      <option value="SENT">已发出</option>
                      <option value="FAILED">发送失败</option>
                    </select>
                  </Field>
                  <Field label="失败原因（失败时必填）">
                    <input
                      className={control}
                      value={dispatchFailure}
                      onChange={(e) => setDispatchFailure(e.target.value)}
                      placeholder="例如 平台接口返回 503"
                    />
                  </Field>
                  <Field label="平台回执（可空）">
                    <input
                      className={control}
                      value={dispatchReceipt}
                      onChange={(e) => setDispatchReceipt(e.target.value)}
                      placeholder="平台侧拿到的回执"
                    />
                  </Field>
                </div>
                <div className="mt-3">
                  <Button onClick={() => void submitDispatch()} disabled={busy}>
                    登记发送结果
                  </Button>
                </div>
              </SectionCard>
            </>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 统计 */}
      {view === 'stats' && (
        <>
          <SectionCard title="触达统计（服务端算）">
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard label="触达总数" value={stats?.totalTasks ?? 0} polarity="neutral" />
              <StatCard label="已发出" value={stats?.sentTasks ?? 0} polarity="positive" />
              <StatCard label="发送失败" value={stats?.failedTasks ?? 0} polarity="negative" />
              <StatCard label="被拦截" value={stats?.blockedTasks ?? 0} polarity="negative" />
              <StatCard
                label="退订拦截"
                value={stats?.optOutBlockedCount ?? 0}
                polarity="negative"
              />
              <StatCard
                label="频次超限"
                value={stats?.frequencyBlockedCount ?? 0}
                polarity="negative"
              />
              <StatCard label="打开数" value={stats?.openCount ?? 0} polarity="neutral" />
              <StatCard label="点击数" value={stats?.clickCount ?? 0} polarity="neutral" />
            </div>
            <p className="text-muted-foreground text-copy-13 mt-3">
              {stats ? careReminderStatsText(stats.note) : '正在读取统计……'}
            </p>
            <p className="text-muted-foreground mt-1 text-xs">
              ⚠️
              这些数字全部由**服务端聚合**，页面**不做任何算术**（自己再算一遍打开率，两处口径迟早会打架）。
            </p>
          </SectionCard>

          <SectionCard title="打开 / 点击统计">
            <div className="border-border bg-muted/40 mb-3 rounded-md px-3 py-2">
              <p className="text-copy-13 flex items-start gap-2">
                <ShieldOff className="text-destructive mt-0.5 h-4 w-4 shrink-0" />
                <span>
                  平台打开 / 点击回执**未接入** ⇒ 自动采集端点如实答「未接入」且**不写数据**。
                  统计只能靠**平台 webhook** 或**人工录入** —— 平台接口未接入时不伪造统计数据。
                </span>
              </p>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="哪条触达">
                <select
                  className={control}
                  value={eventTaskId}
                  onChange={(e) => setEventTaskId(e.target.value)}
                >
                  <option value="">请选择</option>
                  {tasks?.items.map((task) => (
                    <option key={task.id} value={task.id}>
                      {task.taskNo} · {task.customerRef}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="事件类型">
                <select
                  className={control}
                  value={eventType}
                  onChange={(e) => setEventType(e.target.value)}
                >
                  <option value="OPEN">打开</option>
                  <option value="CLICK">点击</option>
                </select>
              </Field>
              <Field label="来源（可空）">
                <input
                  className={control}
                  value={eventSource}
                  onChange={(e) => setEventSource(e.target.value)}
                  placeholder="webhook / 人工登记"
                />
              </Field>
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button onClick={() => void loadEvents()} variant="secondary" disabled={busy}>
                查看事件
              </Button>
              <Button
                onClick={() => void loadPlatformReceipt()}
                variant="secondary"
                disabled={busy}
              >
                查平台回执接入状态
              </Button>
              {writable && (
                <Button onClick={() => void submitEvent()} disabled={busy}>
                  录入事件
                </Button>
              )}
            </div>

            {platformReceipt && (
              <p className="text-muted-foreground text-copy-13 mt-3">
                服务端答复：{platformReceipt}
              </p>
            )}

            {!events ? (
              <div className="mt-4">
                <EmptyState
                  icon={<BarChart3 className="h-6 w-6" />}
                  title="还没有查看过某条触达的打开 / 点击事件"
                  description="选一条触达后点「查看事件」。"
                />
              </div>
            ) : events.items.length === 0 ? (
              <div className="mt-4">
                <EmptyState
                  icon={<BarChart3 className="h-6 w-6" />}
                  title="这条触达还没有打开 / 点击事件"
                  description="平台回执未接入时，只能靠平台 webhook 或人工录入。"
                />
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">事件</th>
                    <th className="py-2">时刻</th>
                    <th className="py-2">来源</th>
                  </tr>
                </thead>
                <tbody>
                  {events.items.map((event) => (
                    <tr key={event.id} className="border-border border-t">
                      <td className="py-2">
                        {careReminderEventLabel(event.eventType)} · {event.taskNo}
                      </td>
                      <td className="py-2">{event.occurredAt}</td>
                      <td className="py-2">{event.source || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
