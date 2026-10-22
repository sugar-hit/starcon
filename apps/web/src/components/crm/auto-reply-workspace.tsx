'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  BarChart3,
  CalendarClock,
  HandHelping,
  MessageSquareReply,
  RefreshCw,
  ShieldOff,
  Siren,
} from 'lucide-react';
import type {
  CrmAutoReplyCapabilities,
  CrmAutoReplyDutyCalendarPage,
  CrmAutoReplyEscalationPage,
  CrmAutoReplyRecordPage,
  CrmAutoReplyRulePage,
  CrmAutoReplySettingRow,
  CrmAutoReplyStats,
  CrmAutoReplyTakeoverPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  autoReplyClassificationLabel,
  autoReplyDutyModeLabel,
  autoReplyEscalationReasonLabel,
  autoReplyKillSwitchText,
  autoReplyLadderText,
  autoReplyMomentText,
  autoReplyOutcomeLabel,
  autoReplyStatsText,
  autoReplySuppressedText,
  autoReplyTriggerTypeLabel,
  createAutoReplyCalendar,
  createAutoReplyRule,
  createAutoReplyTakeover,
  deleteAutoReplyCalendar,
  escalateAutoReplyRecord,
  fetchAutoReplyCalendar,
  fetchAutoReplyCapabilities,
  fetchAutoReplyEscalations,
  fetchAutoReplyRecords,
  fetchAutoReplyRules,
  fetchAutoReplySetting,
  fetchAutoReplyStats,
  fetchAutoReplyTakeovers,
  toggleAutoReplySetting,
  triggerAutoReply,
  updateAutoReplyRule,
} from './auto-reply-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.5.1.1 自动回复工作区。
 *
 * <p>页面按「先看清边界与开关、再配规则、然后才动手」的顺序排，因为蓝图加粗句是
 * 「**自动回复必须可一键关闭并留出人工接管**，很多平台对纯自动回复有明确限制」。
 * 界面上第一件要让人看到的就是**一键关闭**与**能力边界**，
 * 而不是一张可以直接点「自动回复」按钮。
 *
 * <ul>
 *   <li><b>🔴 一键关闭</b> —— 页面最上面就是全局开关；关闭**必须**写原因；
 *       关掉后待发的自动回复会被逐条撤销（不得再发）。</li>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台投递、平台自动回复政策、15.4.1.2 工单介入、平台 webhook 等维度
 *       available=false，页面把它们<b>显式灰掉</b>并写清归属模块。</li>
 *   <li><b>规则</b> —— 触发条件三选一（分类 / 关键词 / 无人工介入超时）、
 *       阶梯位置（第几级 / 最短间隔 / 是否转人工）、话术、适用平台。</li>
 *   <li><b>值班日历</b> —— 节假日 / 非营业时段要不要自动回复，<b>租户可配</b>。</li>
 *   <li><b>自动回复记录</b> —— 真分页真筛选；被闸门挡下的**逐条显示后端给的原因原文**。</li>
 *   <li><b>转人工标记 / 人工接管</b> —— 两件输出分开展示：
 *       「该找人了」与「人已经接手了」不是同一件事。</li>
 *   <li><b>统计</b> —— 全部由后端算，页面<b>不做任何算术</b>。</li>
 * </ul>
 */
export function AutoReplyWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.auto_reply.update');

  const [view, setView] = useState<'rules' | 'calendar' | 'records' | 'escalations' | 'stats'>(
    'rules',
  );

  const [capabilities, setCapabilities] = useState<CrmAutoReplyCapabilities | null>(null);
  const [setting, setSetting] = useState<CrmAutoReplySettingRow | null>(null);
  const [rules, setRules] = useState<CrmAutoReplyRulePage | null>(null);
  const [calendar, setCalendar] = useState<CrmAutoReplyDutyCalendarPage | null>(null);
  const [records, setRecords] = useState<CrmAutoReplyRecordPage | null>(null);
  const [escalations, setEscalations] = useState<CrmAutoReplyEscalationPage | null>(null);
  const [takeovers, setTakeovers] = useState<CrmAutoReplyTakeoverPage | null>(null);
  const [stats, setStats] = useState<CrmAutoReplyStats | null>(null);

  // ---- 规则筛选
  const [ruleTrigger, setRuleTrigger] = useState('');
  const [ruleLadder, setRuleLadder] = useState('');
  const [ruleKeyword, setRuleKeyword] = useState('');
  const [rulePage, setRulePage] = useState(0);

  // ---- 记录筛选
  const [recordOutcome, setRecordOutcome] = useState('');
  const [recordKeyword, setRecordKeyword] = useState('');
  const [recordPage, setRecordPage] = useState(0);

  // ---- 转人工筛选
  const [escalationReason, setEscalationReason] = useState('');
  const [escalationTakenOver, setEscalationTakenOver] = useState('');
  const [escalationPage, setEscalationPage] = useState(0);

  // ---- 一键关闭
  const [closeReason, setCloseReason] = useState('');

  // ---- 新建规则
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [ladderCode, setLadderCode] = useState('');
  const [ruleLevel, setRuleLevel] = useState('1');
  const [ruleTriggerType, setRuleTriggerType] = useState('CLASSIFICATION');
  const [ruleLabels, setRuleLabels] = useState('');
  const [ruleKeywords, setRuleKeywords] = useState('');
  const [ruleHumanTimeout, setRuleHumanTimeout] = useState('60');
  const [ruleLevelWait, setRuleLevelWait] = useState('30');
  const [ruleEscalate, setRuleEscalate] = useState(false);
  const [rulePlatforms, setRulePlatforms] = useState('');
  const [ruleBody, setRuleBody] = useState('');

  // ---- 触发
  const [triggerMessageId, setTriggerMessageId] = useState('');

  // ---- 值班日历
  const [calendarScope, setCalendarScope] = useState('GLOBAL');
  const [calendarPlatform, setCalendarPlatform] = useState('');
  const [calendarDate, setCalendarDate] = useState('');
  const [calendarDutyMode, setCalendarDutyMode] = useState('UNATTENDED');
  const [calendarStart, setCalendarStart] = useState('');
  const [calendarEnd, setCalendarEnd] = useState('');
  const [calendarAllowed, setCalendarAllowed] = useState(true);
  const [calendarTimezone, setCalendarTimezone] = useState('UTC');
  const [calendarHoliday, setCalendarHoliday] = useState('');

  // ---- 人工接管 / 转人工
  const [takeoverMessageId, setTakeoverMessageId] = useState('');
  const [takeoverReason, setTakeoverReason] = useState('');
  const [escalateRecordId, setEscalateRecordId] = useState('');
  const [escalateReason, setEscalateReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [
        caps,
        settingResult,
        ruleResult,
        calendarResult,
        recordResult,
        escalationResult,
        takeoverResult,
        statResult,
      ] = await Promise.all([
        fetchAutoReplyCapabilities(),
        fetchAutoReplySetting(),
        fetchAutoReplyRules({
          triggerType: ruleTrigger || undefined,
          ladderCode: ruleLadder || undefined,
          keyword: ruleKeyword || undefined,
          page: rulePage,
          size: 20,
        }),
        fetchAutoReplyCalendar({ page: 0, size: 50 }),
        fetchAutoReplyRecords({
          outcome: recordOutcome || undefined,
          keyword: recordKeyword || undefined,
          page: recordPage,
          size: 20,
        }),
        fetchAutoReplyEscalations({
          reasonCode: escalationReason || undefined,
          takenOver: escalationTakenOver === '' ? undefined : escalationTakenOver === 'yes',
          page: escalationPage,
          size: 20,
        }),
        fetchAutoReplyTakeovers({ page: 0, size: 50 }),
        fetchAutoReplyStats(),
      ]);
      setCapabilities(caps);
      setSetting(settingResult);
      setRules(ruleResult);
      setCalendar(calendarResult);
      setRecords(recordResult);
      setEscalations(escalationResult);
      setTakeovers(takeoverResult);
      setStats(statResult);
    } catch (e) {
      setError(errorText(e, '自动回复数据读取失败'));
    } finally {
      setLoading(false);
    }
  }, [
    ruleTrigger,
    ruleLadder,
    ruleKeyword,
    rulePage,
    recordOutcome,
    recordKeyword,
    recordPage,
    escalationReason,
    escalationTakenOver,
    escalationPage,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const availableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => d.available) ?? [],
    [capabilities],
  );
  const unavailableDimensions = useMemo(
    () => capabilities?.dimensions.filter((d) => !d.available) ?? [],
    [capabilities],
  );
  const hasRuleFilter = Boolean(ruleTrigger || ruleLadder || ruleKeyword);
  const hasRecordFilter = Boolean(recordOutcome || recordKeyword);
  const hasEscalationFilter = Boolean(escalationReason || escalationTakenOver);

  const submitKillSwitch = useCallback(
    async (enabled: boolean) => {
      if (!enabled && !closeReason.trim()) {
        setActionError(
          '关闭自动回复必须写明关闭原因：事后没人知道当时为什么关、该什么时候开回来。',
        );
        return;
      }
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const result = await toggleAutoReplySetting({
          enabled,
          reason: enabled ? null : closeReason.trim(),
        });
        setNotice(result.note);
        setCloseReason('');
        await load();
      } catch (e) {
        setActionError(errorText(e, '一键开关失败'));
      } finally {
        setBusy(false);
      }
    },
    [closeReason, load],
  );

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createAutoReplyRule({
        ruleCode: ruleCode.trim(),
        ruleName: ruleName.trim(),
        ladderCode: ladderCode.trim(),
        level: Number(ruleLevel),
        escalateToHuman: ruleEscalate,
        enabled: true,
        triggerType: ruleTriggerType,
        classificationLabels: ruleTriggerType === 'CLASSIFICATION' ? ruleLabels.trim() : null,
        keywords: ruleTriggerType === 'KEYWORD' ? ruleKeywords.trim() : null,
        matchMode: ruleTriggerType === 'KEYWORD' ? 'ANY' : null,
        platformCodes: rulePlatforms.trim() || null,
        humanTimeoutMinutes: ruleTriggerType === 'HUMAN_TIMEOUT' ? Number(ruleHumanTimeout) : 0,
        levelWaitMinutes: Number(ruleLevel),
        unattendedAutoReplyAllowed: true,
        templateId: null,
        knowledgeVersionId: null,
        subject: null,
        body: ruleBody.trim(),
        note: null,
      });
      setNotice(
        `已创建规则 ${created.ruleCode}（${autoReplyLadderText(created.level, created.escalateToHuman)}）：` +
          `触发条件 ${autoReplyTriggerTypeLabel(created.triggerType)}。` +
          (created.escalateToHuman ? '本级发完即转人工。' : '本级发完不转人工。'),
      );
      setRuleCode('');
      setRuleName('');
      setRuleBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '自动回复规则创建失败'));
    } finally {
      setBusy(false);
    }
  }, [
    ruleCode,
    ruleName,
    ladderCode,
    ruleLevel,
    ruleEscalate,
    ruleTriggerType,
    ruleLabels,
    ruleKeywords,
    ruleHumanTimeout,
    rulePlatforms,
    ruleBody,
    load,
  ]);

  const toggleRuleEnabled = useCallback(
    async (ruleId: string, enabled: boolean) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const current = rules?.items.find((r) => r.id === ruleId);
        await updateAutoReplyRule(ruleId, {
          ruleCode: current?.ruleCode ?? '',
          ruleName: current?.ruleName ?? '',
          ladderCode: current?.ladderCode ?? '',
          level: current?.level ?? 1,
          escalateToHuman: current?.escalateToHuman ?? false,
          enabled: !enabled,
          triggerType: current?.triggerType ?? 'KEYWORD',
          classificationLabels: current?.classificationLabels || null,
          keywords: current?.keywords || null,
          matchMode: current?.matchMode ?? 'ANY',
          platformCodes: current?.platformCodes || null,
          humanTimeoutMinutes: current?.humanTimeoutMinutes ?? 0,
          levelWaitMinutes: current?.levelWaitMinutes ?? 0,
          unattendedAutoReplyAllowed: current?.unattendedAutoReplyAllowed ?? true,
          templateId: current?.templateId ?? null,
          knowledgeVersionId: current?.knowledgeVersionId ?? null,
          subject: current?.subject ?? null,
          body: current?.body ?? '',
          note: current?.note ?? null,
        });
        setNotice(enabled ? '规则已停用：不再参与触发，历史记录仍可读。' : '规则已启用。');
        await load();
      } catch (e) {
        setActionError(errorText(e, '规则开关切换失败'));
      } finally {
        setBusy(false);
      }
    },
    [rules, load],
  );

  const submitCalendar = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createAutoReplyCalendar({
        scope: calendarScope,
        platformCode: calendarScope === 'PLATFORM' ? calendarPlatform.trim().toUpperCase() : null,
        ruleId: null,
        calendarDate: calendarDate.trim(),
        dutyMode: calendarDutyMode,
        dutyStart: calendarStart.trim() || null,
        dutyEnd: calendarEnd.trim() || null,
        autoReplyAllowed: calendarAllowed,
        timezone: calendarTimezone.trim() || null,
        holidayName: calendarHoliday.trim() || null,
        note: null,
      });
      setNotice(
        `已登记 ${created.calendarDate} 的值班安排（${autoReplyDutyModeLabel(created.dutyMode)}）：` +
          (created.autoReplyAllowed
            ? '此时段允许自动回复。'
            : '此时段**不允许**自动回复（会转人工）。'),
      );
      setCalendarDate('');
      setCalendarHoliday('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '值班日历登记失败'));
    } finally {
      setBusy(false);
    }
  }, [
    calendarScope,
    calendarPlatform,
    calendarDate,
    calendarDutyMode,
    calendarStart,
    calendarEnd,
    calendarAllowed,
    calendarTimezone,
    calendarHoliday,
    load,
  ]);

  const removeCalendar = useCallback(
    async (entryId: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteAutoReplyCalendar(entryId);
        setNotice('值班日历条目已删除：只是不再按它判定，不是历史事实。');
        await load();
      } catch (e) {
        setActionError(errorText(e, '值班日历删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitTrigger = useCallback(async () => {
    if (!triggerMessageId.trim()) {
      setActionError('请先填要评估的消息 id（15.1.1.1 消息收件箱里的那条消息）。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await triggerAutoReply({ messageId: triggerMessageId.trim(), at: null });
      setNotice(
        `${result.note}` +
          (result.globalClosed ? `（关闭原因：${result.closedReason}）` : '') +
          (result.conversationTakenOver ? ' 该会话已被人工接管，本次不再自动回复。' : ''),
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '自动回复触发失败'));
    } finally {
      setBusy(false);
    }
  }, [triggerMessageId, load]);

  const submitTakeover = useCallback(async () => {
    if (!takeoverMessageId.trim()) {
      setActionError('请先填要接管的消息 id（接管以「店铺 + 买家」为粒度）。');
      return;
    }
    if (!takeoverReason.trim()) {
      setActionError(
        '标记人工接管必须写明原因：没有理由的接管，事后没人知道客服为什么不再让系统回。',
      );
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createAutoReplyTakeover({
        shopId: null,
        messageId: takeoverMessageId.trim(),
        recordId: null,
        escalationId: null,
        reason: takeoverReason.trim(),
        takenOverAt: null,
        note: null,
      });
      setNotice(
        `已接管会话 ${created.conversationKey}：接管后**同一会话不再自动回复**（接管时刻 ${autoReplyMomentText(created.takenOverAt)}）。`,
      );
      setTakeoverReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '人工接管登记失败'));
    } finally {
      setBusy(false);
    }
  }, [takeoverMessageId, takeoverReason, load]);

  const submitEscalate = useCallback(async () => {
    if (!escalateRecordId.trim()) {
      setActionError('请先填要标记转人工的那条自动回复记录 id。');
      return;
    }
    if (!escalateReason.trim()) {
      setActionError('标记转人工必须写明原因：没有理由的转人工没法复核。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await escalateAutoReplyRecord(escalateRecordId.trim(), {
        reason: escalateReason.trim(),
        note: null,
      });
      setNotice(
        `已标记转人工（${autoReplyEscalationReasonLabel(created.reasonCode)}）：${created.reason}`,
      );
      setEscalateReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '转人工标记失败'));
    } finally {
      setBusy(false);
    }
  }, [escalateRecordId, escalateReason, load]);

  if (loading && !rules) {
    return (
      <SectionCard title="自动回复">
        <p className="text-muted-foreground text-copy-13">正在读取自动回复规则与记录……</p>
      </SectionCard>
    );
  }

  if (error && !rules) {
    return (
      <SectionCard title="自动回复">
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
      {/* ---------------------------------------------------------- 🔴 一键关闭 */}
      <SectionCard title="一键关闭（全局开关）">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={setting?.autoReplyEnabled ? 'success' : 'danger'}>
            {setting?.autoReplyEnabled ? '自动回复开着' : '自动回复已关闭'}
          </Badge>
          {setting?.autoReplyEnabled ? (
            <span className="text-muted-foreground text-copy-13">
              关闭后新触发不再自动发；已排队待发的会逐条撤销（不得再发）。
            </span>
          ) : (
            <span className="text-muted-foreground text-copy-13">
              关闭原因：{setting?.closedReason || '（没有写关闭原因 —— 这不正常）'}
            </span>
          )}
        </div>
        <p className="text-muted-foreground text-copy-13 mt-2">
          {setting ? autoReplyKillSwitchText(setting) : ''}
          {setting && setting.pendingCancelledCount > 0
            ? ` 当前还有 ${setting.pendingCancelledCount} 条待发。`
            : ''}
        </p>
        {writable && (
          <div className="mt-3 flex flex-wrap items-end gap-3">
            {!setting?.autoReplyEnabled ? (
              <Button size="sm" disabled={busy} onClick={() => void submitKillSwitch(true)}>
                重新打开自动回复
              </Button>
            ) : (
              <>
                <div className="min-w-64 flex-1">
                  <Field
                    label="关闭原因（必填）"
                    hint="没有原因的一键关闭，事后没人知道当时为什么关、该什么时候开回来。"
                  >
                    <input
                      className={control}
                      value={closeReason}
                      onChange={(e) => setCloseReason(e.target.value)}
                      placeholder="例如：平台对纯自动回复有限制，先全关"
                    />
                  </Field>
                </div>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void submitKillSwitch(false)}
                >
                  <ShieldOff className="mr-1 h-4 w-4" />
                  一键关闭自动回复
                </Button>
              </>
            )}
          </div>
        )}
      </SectionCard>

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
        <div className="border-border bg-muted/40 mt-3 space-y-1 rounded-md px-3 py-2">
          <p className="text-copy-13 font-medium">平台限制拿不到 ⇒ 不编白名单</p>
          <p className="text-muted-foreground text-copy-13">
            {capabilities?.platformAutoReplyPolicy}
          </p>
          <p className="text-copy-13 mt-2 font-medium">会话口径</p>
          <p className="text-muted-foreground text-copy-13">{capabilities?.conversationScope}</p>
          <p className="text-copy-13 mt-2 font-medium">人工介入的判定依据</p>
          <p className="text-muted-foreground text-copy-13">
            {capabilities?.humanInterventionBasis}
          </p>
          <p className="text-copy-13 mt-2 font-medium">下游</p>
          <p className="text-muted-foreground text-copy-13">{capabilities?.actionPolicy}</p>
          <p className="text-muted-foreground text-copy-13 mt-2">{capabilities?.boundaryNote}</p>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 视图切换 */}
      <div className="flex flex-wrap items-center gap-2">
        <Segmented
          value={view}
          onChange={setView}
          aria-label="自动回复工作区视图"
          options={[
            {
              value: 'rules',
              label: '自动回复规则',
              icon: <MessageSquareReply className="h-4 w-4" />,
            },
            { value: 'calendar', label: '值班日历', icon: <CalendarClock className="h-4 w-4" /> },
            { value: 'records', label: '自动回复记录', icon: <Siren className="h-4 w-4" /> },
            {
              value: 'escalations',
              label: '转人工与接管',
              icon: <HandHelping className="h-4 w-4" />,
            },
            { value: 'stats', label: '统计', icon: <BarChart3 className="h-4 w-4" /> },
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

      {/* ---------------------------------------------------------- 规则 */}
      {view === 'rules' && (
        <>
          <SectionCard title="自动回复规则">
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="触发条件">
                <select
                  className={control}
                  value={ruleTrigger}
                  onChange={(e) => setRuleTrigger(e.target.value)}
                >
                  <option value="">全部</option>
                  {(capabilities?.triggerTypes ?? []).map((type) => (
                    <option key={type} value={type}>
                      {autoReplyTriggerTypeLabel(type)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="阶梯标识">
                <input
                  className={control}
                  value={ruleLadder}
                  onChange={(e) => setRuleLadder(e.target.value)}
                  placeholder="例如 LAD-LOGISTICS"
                />
              </Field>
              <Field label="关键字">
                <input
                  className={control}
                  value={ruleKeyword}
                  onChange={(e) => setRuleKeyword(e.target.value)}
                  placeholder="规则编码 / 名称 / 阶梯"
                />
              </Field>
            </div>

            {!rules || rules.items.length === 0 ? (
              <div className="mt-4">
                {hasRuleFilter ? (
                  <EmptyState
                    icon={<MessageSquareReply className="h-6 w-6" />}
                    title="当前筛选没有匹配的自动回复规则"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有自动回复规则」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<MessageSquareReply className="h-6 w-6" />}
                    title="还没有自动回复规则"
                    description="先在下面配一条：触发条件三选一（分类 / 关键词 / 无人工介入超时），再决定它在阶梯的第几级。"
                  />
                )}
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">规则 / 阶梯</th>
                    <th className="py-2">触发条件</th>
                    <th className="py-2">超时口径</th>
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
                        <div className="text-muted-foreground">
                          {autoReplyLadderText(rule.level, rule.escalateToHuman)}
                        </div>
                      </td>
                      <td className="py-2">
                        <div>{rule.triggerTypeLabel}</div>
                        <div className="text-muted-foreground">
                          {rule.triggerType === 'CLASSIFICATION'
                            ? rule.classificationLabels
                            : rule.triggerType === 'KEYWORD'
                              ? rule.keywords.replace(/\n/g, ' / ')
                              : `无人工介入 ${rule.humanTimeoutMinutes} 分钟`}
                        </div>
                        {rule.platformCodes && (
                          <div className="text-muted-foreground">
                            适用平台：{rule.platformCodes}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        {rule.triggerType === 'HUMAN_TIMEOUT'
                          ? `${rule.humanTimeoutMinutes} 分钟`
                          : rule.level > 1
                            ? `距上一级至少 ${rule.levelWaitMinutes} 分钟`
                            : '—'}
                      </td>
                      <td className="py-2">
                        <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                          {rule.enabled ? '启用' : '停用'}
                        </Badge>
                      </td>
                      <td className="py-2 text-right">
                        {writable && (
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void toggleRuleEnabled(rule.id, rule.enabled)}
                          >
                            {rule.enabled ? '停用' : '启用'}
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
            <SectionCard title="新建自动回复规则">
              <div className="grid gap-3 md:grid-cols-3">
                <Field label="规则编码">
                  <input
                    className={control}
                    value={ruleCode}
                    onChange={(e) => setRuleCode(e.target.value)}
                    placeholder="例如 R-LOG-01"
                  />
                </Field>
                <Field label="规则名称">
                  <input
                    className={control}
                    value={ruleName}
                    onChange={(e) => setRuleName(e.target.value)}
                    placeholder="例如 物流查询自动引导"
                  />
                </Field>
                <Field label="阶梯标识" hint="同一阶梯的多级规则共用它（例如 LAD-LOGISTICS）。">
                  <input
                    className={control}
                    value={ladderCode}
                    onChange={(e) => setLadderCode(e.target.value)}
                    placeholder="例如 LAD-LOGISTICS"
                  />
                </Field>
                <Field label="第几级" hint="第 1 级 = 自动引导；第 2 级起要有最小间隔。">
                  <input
                    className={control}
                    value={ruleLevel}
                    onChange={(e) => setRuleLevel(e.target.value)}
                    inputMode="numeric"
                  />
                </Field>
                <Field label="触发条件（互斥，三选一）">
                  <select
                    className={control}
                    value={ruleTriggerType}
                    onChange={(e) => setRuleTriggerType(e.target.value)}
                  >
                    {(capabilities?.triggerTypes ?? []).map((type) => (
                      <option key={type} value={type}>
                        {autoReplyTriggerTypeLabel(type)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label="本级发完是否转人工"
                  hint="阶梯终点由它标记；转人工标记会真的落库可查。"
                >
                  <select
                    className={control}
                    value={ruleEscalate ? 'yes' : 'no'}
                    onChange={(e) => setRuleEscalate(e.target.value === 'yes')}
                  >
                    <option value="no">不转人工（还有下一级）</option>
                    <option value="yes">转人工（阶梯终点）</option>
                  </select>
                </Field>
                {ruleTriggerType === 'CLASSIFICATION' && (
                  <Field
                    label="命中分类（逗号分隔）"
                    hint="标签来自上游 15.1.1.3 的分类结论，不另造分类词表。"
                  >
                    <select
                      className={control}
                      multiple
                      value={ruleLabels.split(',').filter(Boolean)}
                      onChange={(e) =>
                        setRuleLabels(
                          Array.from(e.target.selectedOptions)
                            .map((o) => o.value)
                            .join(','),
                        )
                      }
                    >
                      {(capabilities?.classificationLabels ?? []).map((label) => (
                        <option key={label} value={label}>
                          {autoReplyClassificationLabel(label)}
                        </option>
                      ))}
                    </select>
                  </Field>
                )}
                {ruleTriggerType === 'KEYWORD' && (
                  <Field label="关键词（换行分隔）" hint="与上游 15.1.1.3 的关键词口径一致。">
                    <textarea
                      className={control}
                      rows={3}
                      value={ruleKeywords}
                      onChange={(e) => setRuleKeywords(e.target.value)}
                      placeholder={'退款\n退货'}
                    />
                  </Field>
                )}
                {ruleTriggerType === 'HUMAN_TIMEOUT' && (
                  <Field label="无人工介入超时（分钟）" hint="超过这个分钟数仍没人回，才触发。">
                    <input
                      className={control}
                      value={ruleHumanTimeout}
                      onChange={(e) => setRuleHumanTimeout(e.target.value)}
                      inputMode="numeric"
                    />
                  </Field>
                )}
                <Field
                  label="距上一级的最小间隔（分钟）"
                  hint="第 2 级起必填：间隔为 0 会让一次触发把整条阶梯一次走完。"
                >
                  <input
                    className={control}
                    value={ruleLevelWait}
                    onChange={(e) => setRuleLevelWait(e.target.value)}
                    inputMode="numeric"
                  />
                </Field>
                <Field label="适用平台（逗号分隔，空 = 全部）">
                  <input
                    className={control}
                    value={rulePlatforms}
                    onChange={(e) => setRulePlatforms(e.target.value)}
                    placeholder="例如 SHOPIFY"
                  />
                </Field>
                <Field label="回复正文" hint="也可以在 15.1.1.2 里配模板 —— 这里的正文是兜底。">
                  <textarea
                    className={control}
                    rows={3}
                    value={ruleBody}
                    onChange={(e) => setRuleBody(e.target.value)}
                    placeholder="您好，包裹正在派送中……"
                  />
                </Field>
              </div>
              <div className="mt-3">
                <Button size="sm" disabled={busy} onClick={() => void submitRule()}>
                  创建规则
                </Button>
              </div>
            </SectionCard>
          )}

          {writable && (
            <SectionCard title="触发一次自动回复评估">
              <p className="text-muted-foreground text-copy-13">
                ⚠️ 平台站内信同步**未接入** ⇒ 触发入口由调用方**显式**调用；
                平台适配器接入后调**同一个**入口。填 15.1.1.1 消息收件箱里那条消息的 id。
              </p>
              <div className="mt-3 grid gap-3 md:grid-cols-3">
                <Field label="消息 id">
                  <input
                    className={control}
                    value={triggerMessageId}
                    onChange={(e) => setTriggerMessageId(e.target.value)}
                    placeholder="crm_message.id"
                  />
                </Field>
              </div>
              <div className="mt-3">
                <Button size="sm" disabled={busy} onClick={() => void submitTrigger()}>
                  触发评估
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 值班日历 */}
      {view === 'calendar' && (
        <>
          <SectionCard title="值班日历（节假日 / 非营业时段）">
            <p className="text-muted-foreground text-copy-13">
              🔴 节假日不是写死的常量 —— 每个租户的排班都不一样。 「此时段要不要自动回复」由{' '}
              <code>autoReplyAllowed</code> 这一列决定； 挡下自动回复时会<b>转人工</b>
              （留人工出口）。
            </p>
            {!calendar || calendar.items.length === 0 ? (
              <div className="mt-4">
                <EmptyState
                  icon={<CalendarClock className="h-6 w-6" />}
                  title="还没有排班记录"
                  description="没有排班时按规则的默认口径（允许自动回复）判定 —— 想在节假日改成人工，请登记一条值班日历。"
                />
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">日期</th>
                    <th className="py-2">范围</th>
                    <th className="py-2">值班安排</th>
                    <th className="py-2">是否允许自动回复</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {calendar.items.map((entry) => (
                    <tr key={entry.id} className="border-border border-t">
                      <td className="py-2">
                        <div>{entry.calendarDate}</div>
                        {entry.holidayName && (
                          <div className="text-muted-foreground">{entry.holidayName}</div>
                        )}
                      </td>
                      <td className="py-2">
                        <div>{entry.scopeLabel}</div>
                        <div className="text-muted-foreground">
                          {entry.platformCode || entry.ruleCode || '—'}
                        </div>
                      </td>
                      <td className="py-2">
                        <div>{entry.dutyModeLabel}</div>
                        <div className="text-muted-foreground">
                          {entry.dutyStart ? `${entry.dutyStart} - ${entry.dutyEnd}` : '全天'}
                        </div>
                      </td>
                      <td className="py-2">
                        <Badge tone={entry.autoReplyAllowed ? 'success' : 'warning'}>
                          {entry.autoReplyAllowed ? '允许' : '不允许（转人工）'}
                        </Badge>
                      </td>
                      <td className="py-2 text-right">
                        {writable && (
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void removeCalendar(entry.id)}
                          >
                            删除
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {writable && (
            <SectionCard title="登记值班安排">
              <div className="grid gap-3 md:grid-cols-3">
                <Field label="生效范围">
                  <select
                    className={control}
                    value={calendarScope}
                    onChange={(e) => setCalendarScope(e.target.value)}
                  >
                    {(capabilities?.dutyScopes ?? []).map((scope) => (
                      <option key={scope} value={scope}>
                        {scope === 'GLOBAL' ? '整租户' : scope === 'PLATFORM' ? '按平台' : '按规则'}
                      </option>
                    ))}
                  </select>
                </Field>
                {calendarScope === 'PLATFORM' && (
                  <Field label="平台">
                    <input
                      className={control}
                      value={calendarPlatform}
                      onChange={(e) => setCalendarPlatform(e.target.value)}
                      placeholder="例如 SHOPIFY"
                    />
                  </Field>
                )}
                <Field label="生效日期">
                  <input
                    className={control}
                    type="date"
                    value={calendarDate}
                    onChange={(e) => setCalendarDate(e.target.value)}
                  />
                </Field>
                <Field label="值班安排">
                  <select
                    className={control}
                    value={calendarDutyMode}
                    onChange={(e) => setCalendarDutyMode(e.target.value)}
                  >
                    {(capabilities?.dutyModes ?? []).map((mode) => (
                      <option key={mode} value={mode}>
                        {autoReplyDutyModeLabel(mode)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="时段起（HH:mm，留空 = 全天）">
                  <input
                    className={control}
                    value={calendarStart}
                    onChange={(e) => setCalendarStart(e.target.value)}
                    placeholder="09:00"
                  />
                </Field>
                <Field label="时段止（HH:mm，与起始同时填）">
                  <input
                    className={control}
                    value={calendarEnd}
                    onChange={(e) => setCalendarEnd(e.target.value)}
                    placeholder="18:00"
                  />
                </Field>
                <Field label="此时段是否允许自动回复">
                  <select
                    className={control}
                    value={calendarAllowed ? 'yes' : 'no'}
                    onChange={(e) => setCalendarAllowed(e.target.value === 'yes')}
                  >
                    <option value="yes">允许（无人值守也自动回）</option>
                    <option value="no">不允许（转人工）</option>
                  </select>
                </Field>
                <Field label="时区">
                  <input
                    className={control}
                    value={calendarTimezone}
                    onChange={(e) => setCalendarTimezone(e.target.value)}
                    placeholder="Asia/Shanghai"
                  />
                </Field>
                <Field label="节日名">
                  <input
                    className={control}
                    value={calendarHoliday}
                    onChange={(e) => setCalendarHoliday(e.target.value)}
                    placeholder="例如 春节"
                  />
                </Field>
              </div>
              <div className="mt-3">
                <Button size="sm" disabled={busy} onClick={() => void submitCalendar()}>
                  登记值班安排
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 记录 */}
      {view === 'records' && (
        <SectionCard title="自动回复记录">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="结果">
              <select
                className={control}
                value={recordOutcome}
                onChange={(e) => setRecordOutcome(e.target.value)}
              >
                <option value="">全部</option>
                {(capabilities?.outcomes ?? []).map((outcome) => (
                  <option key={outcome} value={outcome}>
                    {autoReplyOutcomeLabel(outcome)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="关键字">
              <input
                className={control}
                value={recordKeyword}
                onChange={(e) => setRecordKeyword(e.target.value)}
                placeholder="记录号 / 规则 / 消息号 / 买家"
              />
            </Field>
          </div>

          {!records || records.items.length === 0 ? (
            <div className="mt-4">
              {hasRecordFilter ? (
                <EmptyState
                  icon={<Siren className="h-6 w-6" />}
                  title="当前筛选没有匹配的自动回复记录"
                  description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有自动回复过」是两件不同的事。"
                />
              ) : (
                <EmptyState
                  icon={<Siren className="h-6 w-6" />}
                  title="还没有自动回复记录"
                  description="配好规则后触发一次评估，这里会出现记录 —— 命中的规则、发出去的正文、没发出去的原因都在里面。"
                />
              )}
            </div>
          ) : (
            <table className="text-copy-13 mt-4 w-full text-left">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2">记录</th>
                  <th className="py-2">命中 / 阶梯</th>
                  <th className="py-2">结果</th>
                  <th className="py-2">发送</th>
                  <th className="py-2">时刻</th>
                </tr>
              </thead>
              <tbody>
                {records.items.map((record) => (
                  <tr key={record.id} className="border-border border-t">
                    <td className="py-2">
                      <div className="font-medium">{record.recordNo}</div>
                      <div className="text-muted-foreground">
                        {record.messageNo} · {record.buyerReference}
                      </div>
                    </td>
                    <td className="py-2">
                      <div>{record.ruleCode || '—'}</div>
                      <div className="text-muted-foreground">
                        {autoReplyLadderText(record.level, record.escalated)}
                      </div>
                      <div className="text-muted-foreground">{record.triggerReason}</div>
                    </td>
                    <td className="py-2">
                      <Badge
                        tone={
                          record.outcome === 'SEND_REGISTERED'
                            ? 'success'
                            : record.outcome === 'CANCELLED'
                              ? 'neutral'
                              : 'warning'
                        }
                      >
                        {record.outcomeLabel}
                      </Badge>
                      {record.reason && (
                        <div className="text-muted-foreground mt-1">
                          {autoReplySuppressedText(record.reason)}
                        </div>
                      )}
                    </td>
                    <td className="py-2">
                      {record.sendNo ? (
                        <>
                          <div>{record.sendNo}</div>
                          <div className="text-muted-foreground">
                            {record.sendStatus === 'QUEUED'
                              ? '已登记待发（平台发送接口未接入）'
                              : record.sendStatus}
                          </div>
                          {record.sendFailureReason && (
                            <div className="text-destructive">{record.sendFailureReason}</div>
                          )}
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="py-2">{autoReplyMomentText(record.occurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {records && records.totalPages > 1 && (
            <div className="mt-3 flex items-center gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={recordPage <= 0}
                onClick={() => setRecordPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-copy-13">
                第 {records.page + 1} / {records.totalPages} 页（合计 {records.totalElements} 条）
              </span>
              <Button
                size="sm"
                variant="secondary"
                disabled={recordPage + 1 >= records.totalPages}
                onClick={() => setRecordPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 转人工与接管 */}
      {view === 'escalations' && (
        <>
          <SectionCard title="转人工标记">
            <p className="text-muted-foreground text-copy-13">
              🔴 「该找人了」与「人已经接手了」是<b>两件</b>输出，分开展示。
              转人工标记**只追加**，一条消息只转人工一次。
            </p>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <Field label="原因">
                <select
                  className={control}
                  value={escalationReason}
                  onChange={(e) => setEscalationReason(e.target.value)}
                >
                  <option value="">全部</option>
                  {(capabilities?.escalationReasons ?? []).map((reason) => (
                    <option key={reason} value={reason}>
                      {autoReplyEscalationReasonLabel(reason)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="是否已接管">
                <select
                  className={control}
                  value={escalationTakenOver}
                  onChange={(e) => setEscalationTakenOver(e.target.value)}
                >
                  <option value="">全部</option>
                  <option value="no">还没人接手</option>
                  <option value="yes">已接手</option>
                </select>
              </Field>
            </div>

            {!escalations || escalations.items.length === 0 ? (
              <div className="mt-4">
                {hasEscalationFilter ? (
                  <EmptyState
                    icon={<HandHelping className="h-6 w-6" />}
                    title="当前筛选没有匹配转人工标记"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有待人工处理的事」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<HandHelping className="h-6 w-6" />}
                    title="没有待人工处理的转人工标记"
                    description="阶梯走完、值班闸挡下、发送登记失败时，这里会出现一条需要人接手的记录。"
                  />
                )}
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">消息 / 买家</th>
                    <th className="py-2">原因</th>
                    <th className="py-2">是否已接管</th>
                    <th className="py-2">时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {escalations.items.map((escalation) => (
                    <tr key={escalation.id} className="border-border border-t">
                      <td className="py-2">
                        <div>{escalation.messageNo}</div>
                        <div className="text-muted-foreground">{escalation.buyerReference}</div>
                      </td>
                      <td className="py-2">
                        <Badge tone="warning">{escalation.reasonCodeLabel}</Badge>
                        <div className="text-muted-foreground mt-1">{escalation.reason}</div>
                      </td>
                      <td className="py-2">
                        <Badge tone={escalation.takenOver ? 'success' : 'neutral'}>
                          {escalation.takenOver ? '已接管' : '待接管'}
                        </Badge>
                      </td>
                      <td className="py-2">{autoReplyMomentText(escalation.escalatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {escalations && escalations.totalPages > 1 && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={escalationPage <= 0}
                  onClick={() => setEscalationPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-copy-13">
                  第 {escalations.page + 1} / {escalations.totalPages} 页（合计{' '}
                  {escalations.totalElements} 条）
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={escalationPage + 1 >= escalations.totalPages}
                  onClick={() => setEscalationPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {writable && (
            <SectionCard title="标记人工接管 / 手动转人工">
              <div className="grid gap-3 md:grid-cols-3">
                <Field
                  label="要接管的消息 id"
                  hint="接管以「店铺 + 买家」为粒度；接管后同一会话不再自动回复。"
                >
                  <input
                    className={control}
                    value={takeoverMessageId}
                    onChange={(e) => setTakeoverMessageId(e.target.value)}
                    placeholder="crm_message.id"
                  />
                </Field>
                <Field label="接管原因（必填）">
                  <input
                    className={control}
                    value={takeoverReason}
                    onChange={(e) => setTakeoverReason(e.target.value)}
                    placeholder="例如 客服接手了"
                  />
                </Field>
                <Field label="要标记转人工的记录 id">
                  <input
                    className={control}
                    value={escalateRecordId}
                    onChange={(e) => setEscalateRecordId(e.target.value)}
                    placeholder="crm_auto_reply_record.id"
                  />
                </Field>
                <Field label="转人工原因（必填）">
                  <input
                    className={control}
                    value={escalateReason}
                    onChange={(e) => setEscalateReason(e.target.value)}
                    placeholder="例如 买家情绪激动"
                  />
                </Field>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" disabled={busy} onClick={() => void submitTakeover()}>
                  标记人工接管
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void submitEscalate()}
                >
                  标记转人工
                </Button>
              </div>
            </SectionCard>
          )}

          <SectionCard title="人工接管记录">
            {!takeovers || takeovers.items.length === 0 ? (
              <div className="mt-4">
                <EmptyState
                  icon={<HandHelping className="h-6 w-6" />}
                  title="还没有人工接管"
                  description="接管是**硬闸**：接管之后，同一个会话不再自动回复。"
                />
              </div>
            ) : (
              <table className="text-copy-13 mt-4 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">会话</th>
                    <th className="py-2">原因</th>
                    <th className="py-2">时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {takeovers.items.map((takeover) => (
                    <tr key={takeover.id} className="border-border border-t">
                      <td className="py-2">
                        <div>{takeover.conversationKey}</div>
                        <div className="text-muted-foreground">{takeover.shopName}</div>
                      </td>
                      <td className="py-2">{takeover.reason}</td>
                      <td className="py-2">{autoReplyMomentText(takeover.takenOverAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </>
      )}

      {/* ---------------------------------------------------------- 统计 */}
      {view === 'stats' && (
        <SectionCard title="统计">
          {!stats ? (
            <div className="mt-4">
              <EmptyState
                icon={<BarChart3 className="h-6 w-6" />}
                title="还没有统计口径"
                description="统计全部由后端算好，页面不做任何算术。"
              />
            </div>
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-4">
                <StatCard label="自动回复记录总数" value={stats.totalRecords} />
                <StatCard label="已登记发送" value={stats.sendRegistered} />
                <StatCard label="被闸门挡下" value={stats.suppressed} />
                <StatCard label="已撤销" value={stats.cancelled} />
                <StatCard label="转人工标记" value={stats.escalations} />
                <StatCard label="其中已接管" value={stats.escalationsTakenOver} />
                <StatCard label="人工接管" value={stats.takeovers} />
                <StatCard label="第 1 级（自动引导）" value={stats.recordsByLevelOne} />
              </div>
              <p className="text-muted-foreground text-copy-13 mt-3">
                {autoReplyStatsText(stats.note)}
              </p>
              <div className="border-border bg-muted/40 mt-3 rounded-md px-3 py-2">
                <p className="text-copy-13 font-medium">按结果</p>
                <ul className="text-muted-foreground text-copy-13 mt-1">
                  {stats.byOutcome.map((bucket) => (
                    <li key={bucket.code}>
                      {bucket.label}：{bucket.count}
                    </li>
                  ))}
                </ul>
                <p className="text-copy-13 mt-2 font-medium">按触发条件</p>
                <ul className="text-muted-foreground text-copy-13 mt-1">
                  {stats.byTriggerType.map((bucket) => (
                    <li key={bucket.code}>
                      {bucket.label}：{bucket.count}
                    </li>
                  ))}
                </ul>
              </div>
              <p className="text-muted-foreground text-copy-13 mt-3 flex items-start gap-2">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                页面**不重算**任何比率 —— 分子分母各取一份、两个页面各算一次，口径迟早会打架。
              </p>
            </>
          )}
        </SectionCard>
      )}
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
