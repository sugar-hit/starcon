'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import {
  BellOff,
  FileWarning,
  ListChecks,
  RefreshCw,
  Search,
  Settings2,
  Tags,
  TriangleAlert,
} from 'lucide-react';
import type {
  CrmEmailRuleAssignmentPage,
  CrmEmailRuleAssignmentRow,
  CrmEmailRuleCapabilities,
  CrmEmailRuleHitDetail,
  CrmEmailRuleHitPage,
  CrmEmailRuleOptions,
  CrmEmailRulePage,
  CrmEmailRuleRow,
  CrmEmailRuleStatistics,
  CrmEmailTicketTagPage,
  CrmEmailTicketTagRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  actionLabel,
  assignEmailTag,
  assignmentText,
  cancelEmailRuleAssignment,
  createEmailRule,
  createEmailRuleTag,
  deleteEmailRule,
  deleteEmailRuleTag,
  evaluateEmail,
  fetchEmailRuleAssignments,
  fetchEmailRuleCapabilities,
  fetchEmailRuleHit,
  fetchEmailRuleHits,
  fetchEmailRuleOptions,
  fetchEmailRuleStatistics,
  fetchEmailRuleTags,
  fetchEmailRules,
  hasActiveFilter,
  hitActionText,
  hitText,
  importantSourceLabel,
  matchBasisText,
  matchModeLabel,
  parseCodeDraft,
  parseKeywordDraft,
  ruleStatText,
  ruleText,
  snapshotText,
  statisticsText,
  targetTypeLabel,
  updateEmailRule,
  type EmailRuleAssignmentParams,
  type EmailRuleDraft,
  type EmailRuleHitParams,
  type EmailRuleParams,
  type EmailRuleTagParams,
} from './email-rule-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.1.2.2 邮件规则工作区。
 *
 * <p>页面按「先看清边界、再配规则、再打标签、再评估、最后看留痕」的顺序排，因为
 * 蓝图加粗句是「**关键沟通静默会造成平台违规**，规则需强制留痕」。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       15.4.1.1 工单分配与 15.5.1 自动回复两维是 available=false，页面把它们
 *       <b>显式灰掉</b>并写清归属模块，<b>不提供</b>任何看起来能点的假入口。</li>
 *   <li><b>邮件规则</b> —— 三个条件维度（发件人 / 关键词 / 工单标签）+ 显式顺序 +
 *       启用状态 + 版本，全部真配真存；<b>至少要有一个维度</b>
 *       （全空的规则永远不会命中，却会占掉一个顺序位）。</li>
 *   <li><b>工单标签</b> —— 规则的第三个维度引用的标签字典；标签必须**真实存在**，
 *       否则那条规则永远不会命中。</li>
 *   <li><b>评估</b> —— 按顺序评估，第一条命中即生效；命中记录<b>只追加</b>。</li>
 *   <li><b>命中记录</b> —— 每行都显示<b>规则声明的动作</b>与<b>实际生效的动作</b>；
 *       静默被拒时用告警色标出并显示理由。</li>
 *   <li><b>自动分配结果</b> —— 分给谁、依据哪条规则；状态恒为「等下游取」
 *       （15.4.1.1 未交付 ⇒ <b>没有工单</b>）。</li>
 * </ul>
 */
export function EmailRuleWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.emailrule.update');

  const [capabilities, setCapabilities] = useState<CrmEmailRuleCapabilities | null>(null);
  const [options, setOptions] = useState<CrmEmailRuleOptions | null>(null);
  const [rules, setRules] = useState<CrmEmailRulePage | null>(null);
  const [tags, setTags] = useState<CrmEmailTicketTagPage | null>(null);
  const [hits, setHits] = useState<CrmEmailRuleHitPage | null>(null);
  const [assignments, setAssignments] = useState<CrmEmailRuleAssignmentPage | null>(null);
  const [statistics, setStatistics] = useState<CrmEmailRuleStatistics | null>(null);
  const [detail, setDetail] = useState<CrmEmailRuleHitDetail | null>(null);

  // 规则筛选
  const [ruleKeyword, setRuleKeyword] = useState('');
  const [ruleAction, setRuleAction] = useState('');
  const [ruleEnabled, setRuleEnabled] = useState('');
  const [rulePageIndex, setRulePageIndex] = useState(0);

  // 命中记录筛选
  const [hitKeyword, setHitKeyword] = useState('');
  const [hitRuleCode, setHitRuleCode] = useState('');
  const [refusedOnly, setRefusedOnly] = useState(false);
  const [importantOnly, setImportantOnly] = useState(false);
  const [hitPageIndex, setHitPageIndex] = useState(0);

  // 规则草稿
  const [editingRule, setEditingRule] = useState<CrmEmailRuleRow | null>(null);
  const [draftCode, setDraftCode] = useState('');
  const [draftName, setDraftName] = useState('');
  const [draftOrder, setDraftOrder] = useState('100');
  const [draftAction, setDraftAction] = useState('AUTO_ASSIGN');
  const [draftSenderEmails, setDraftSenderEmails] = useState('');
  const [draftSenderDomains, setDraftSenderDomains] = useState('');
  const [draftKeywords, setDraftKeywords] = useState('');
  const [draftTagCodes, setDraftTagCodes] = useState('');
  const [draftMatchMode, setDraftMatchMode] = useState('ANY');
  const [draftTargetType, setDraftTargetType] = useState('TEAM');
  const [draftTargetCode, setDraftTargetCode] = useState('');
  const [draftReplyTemplate, setDraftReplyTemplate] = useState('');
  const [draftReplySubject, setDraftReplySubject] = useState('');
  const [draftReplyBody, setDraftReplyBody] = useState('');
  const [draftMarkers, setDraftMarkers] = useState('');
  const [draftEnabled, setDraftEnabled] = useState(true);
  const [draftNote, setDraftNote] = useState('');

  // 工单标签草稿
  const [tagKeyword, setTagKeyword] = useState('');
  const [tagCode, setTagCode] = useState('');
  const [tagName, setTagName] = useState('');
  const [tagDescription, setTagDescription] = useState('');

  // 打标与评估
  const [emailId, setEmailId] = useState('');
  const [tagForEmail, setTagForEmail] = useState('');
  const [evaluateResult, setEvaluateResult] = useState<string | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const ruleParams = useMemo<EmailRuleParams>(
    () => ({
      keyword: ruleKeyword || undefined,
      action: ruleAction || undefined,
      enabled: ruleEnabled === '' ? undefined : ruleEnabled === 'true',
      page: rulePageIndex,
      size: 20,
    }),
    [ruleKeyword, ruleAction, ruleEnabled, rulePageIndex],
  );

  const hitParams = useMemo<EmailRuleHitParams>(
    () => ({
      keyword: hitKeyword || undefined,
      ruleCode: hitRuleCode || undefined,
      silenceRefused: refusedOnly ? true : undefined,
      important: importantOnly ? true : undefined,
      page: hitPageIndex,
      size: 20,
    }),
    [hitKeyword, hitRuleCode, refusedOnly, importantOnly, hitPageIndex],
  );

  const assignmentParams = useMemo<EmailRuleAssignmentParams>(() => ({ page: 0, size: 20 }), []);

  const tagParams = useMemo<EmailRuleTagParams>(
    () => ({ keyword: tagKeyword || undefined, page: 0, size: 20 }),
    [tagKeyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, opts, ruleList, tagList, hitList, assignmentList, stats] = await Promise.all([
        fetchEmailRuleCapabilities(),
        fetchEmailRuleOptions(),
        fetchEmailRules(ruleParams),
        fetchEmailRuleTags(tagParams),
        fetchEmailRuleHits(hitParams),
        fetchEmailRuleAssignments(assignmentParams),
        fetchEmailRuleStatistics(),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setRules(ruleList);
      setTags(tagList);
      setHits(hitList);
      setAssignments(assignmentList);
      setStatistics(stats);
    } catch (e) {
      setError(errorText(e, '邮件规则加载失败'));
    } finally {
      setLoading(false);
    }
  }, [ruleParams, hitParams, assignmentParams, tagParams]);

  useEffect(() => {
    void load();
  }, [load]);

  const openHit = useCallback(async (hitId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchEmailRuleHit(hitId));
    } catch (e) {
      setActionError(errorText(e, '命中记录详情加载失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const ruleDraft = useCallback((): EmailRuleDraft => {
    return {
      ruleCode: draftCode.trim(),
      ruleName: draftName.trim(),
      priorityOrder: Number(draftOrder) || 0,
      enabled: draftEnabled,
      action: draftAction,
      senderEmails: parseKeywordDraft(draftSenderEmails),
      senderDomains: parseCodeDraft(draftSenderDomains),
      keywords: parseKeywordDraft(draftKeywords),
      ticketTagCodes: parseCodeDraft(draftTagCodes),
      matchMode: draftMatchMode,
      assignTargetType: draftTargetType,
      assignTargetCode: draftTargetCode.trim(),
      replyTemplateCode: draftReplyTemplate.trim(),
      replySubject: draftReplySubject,
      replyBody: draftReplyBody,
      importantMarkers: parseKeywordDraft(draftMarkers),
      note: draftNote,
    };
  }, [
    draftCode,
    draftName,
    draftOrder,
    draftEnabled,
    draftAction,
    draftSenderEmails,
    draftSenderDomains,
    draftKeywords,
    draftTagCodes,
    draftMatchMode,
    draftTargetType,
    draftTargetCode,
    draftReplyTemplate,
    draftReplySubject,
    draftReplyBody,
    draftMarkers,
    draftNote,
  ]);

  const saveRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const draft = ruleDraft();
      if (editingRule) {
        const result = await updateEmailRule(editingRule.id, draft);
        setNotice(result.note);
      } else {
        const result = await createEmailRule(draft);
        setNotice(result.note);
      }
      setEditingRule(null);
      setDraftCode('');
      setDraftName('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '规则保存失败'));
    } finally {
      setBusy(false);
    }
  }, [editingRule, ruleDraft, load]);

  const removeRule = useCallback(
    async (rule: CrmEmailRuleRow) => {
      setBusy(true);
      setActionError(null);
      try {
        const result = await deleteEmailRule(rule.id);
        setNotice(result.note);
        await load();
      } catch (e) {
        setActionError(errorText(e, '规则删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const saveTag = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await createEmailRuleTag({
        tagCode: tagCode.trim(),
        name: tagName.trim(),
        status: 'ACTIVE',
        description: tagDescription,
      });
      setNotice(result.note);
      setTagCode('');
      setTagName('');
      setTagDescription('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '工单标签保存失败'));
    } finally {
      setBusy(false);
    }
  }, [tagCode, tagName, tagDescription, load]);

  const removeTag = useCallback(
    async (tag: CrmEmailTicketTagRow) => {
      setBusy(true);
      setActionError(null);
      try {
        const result = await deleteEmailRuleTag(tag.id);
        setNotice(result.note);
        await load();
      } catch (e) {
        setActionError(errorText(e, '工单标签删除失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const tagEmail = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await assignEmailTag(emailId.trim(), tagForEmail.trim());
      setNotice(
        `已给邮件打上标签 ${result.tagCode}（${result.tagName}）：标签会参与规则的「工单标签」维度判定。`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '打标失败'));
    } finally {
      setBusy(false);
    }
  }, [emailId, tagForEmail, load]);

  const runEvaluate = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setEvaluateResult(null);
    try {
      const result = await evaluateEmail(emailId.trim());
      setEvaluateResult(
        [
          result.matched ? `命中规则 ${result.ruleCode ?? ''}` : '没有规则命中',
          result.action ? actionLabel(result.action) : '',
          result.effectiveAction && result.effectiveAction !== result.action
            ? `实际生效 ${actionLabel(result.effectiveAction)}`
            : '',
          result.silenceRefused ? '⚠️ 静默被拒（重要沟通不可静默）' : '',
          result.reason,
        ]
          .filter(Boolean)
          .join(' · '),
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '评估失败'));
    } finally {
      setBusy(false);
    }
  }, [emailId, load]);

  const cancelAssignment = useCallback(
    async (assignment: CrmEmailRuleAssignmentRow) => {
      setBusy(true);
      setActionError(null);
      try {
        const result = await cancelEmailRuleAssignment(assignment.id, '运营手动取消（分错了组）');
        setNotice(`已取消分配结果 ${result.assignmentNo}：${result.cancelReason}`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '取消分配结果失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  if (loading) {
    return <p className="text-muted-foreground text-copy-13">正在加载邮件规则…</p>;
  }
  if (error) {
    return (
      <div className="space-y-2">
        <p className="text-destructive text-copy-13">{error}</p>
        <Button size="sm" variant="secondary" onClick={() => void load()}>
          重试
        </Button>
      </div>
    );
  }

  const ruleItems = rules?.items ?? [];
  const hitItems = hits?.items ?? [];
  const tagItems = tags?.items ?? [];
  const assignmentItems = assignments?.items ?? [];

  return (
    <div className="space-y-4">
      {actionError && (
        <p className="text-destructive text-copy-13 border-destructive rounded border px-3 py-2">
          {actionError}
        </p>
      )}
      {notice && <p className="text-copy-13 border-border rounded border px-3 py-2">{notice}</p>}

      {/* ---------------------------------------------------------- 能力声明 */}
      <SectionCard
        title="能力声明"
        description="每一维说清「有没有、依据是什么、缺了会怎样」；未交付的下游不会出现在页面上的按钮里。"
      >
        <ul className="text-copy-13 space-y-2">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="border-border rounded-md px-3 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={dimension.available ? 'success' : 'warning'}>
                  {dimension.available ? '可用' : '未交付'}
                </Badge>
                <span className="font-medium">{dimension.description}</span>
                {dimension.ownerModule && (
                  <span className="text-muted-foreground text-copy-12">
                    归属模块 {dimension.ownerModule}
                  </span>
                )}
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">{dimension.source}</p>
              <p className="text-copy-12 mt-1">{dimension.impact}</p>
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-copy-13 border-border mt-3 rounded border px-3 py-2">
            {capabilities.highlight}
          </p>
        )}
        {capabilities?.boundaryNote && (
          <p className="text-muted-foreground text-copy-12 mt-2">{capabilities.boundaryNote}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 邮件规则 */}
      <SectionCard
        title="邮件规则"
        description="三个条件维度（发件人 / 关键词 / 工单标签）+ 显式顺序 + 启用状态 + 版本，全部真配真存。"
        extra={
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-3">
            <input
              className={control}
              value={ruleKeyword}
              onChange={(e) => {
                setRuleKeyword(e.target.value);
                setRulePageIndex(0);
              }}
              placeholder="规则编码 / 名称 / 关键词"
            />
            <select
              className={control}
              value={ruleAction}
              onChange={(e) => {
                setRuleAction(e.target.value);
                setRulePageIndex(0);
              }}
            >
              <option value="">全部动作</option>
              {(options?.actions ?? []).map((value) => (
                <option key={value} value={value}>
                  {actionLabel(value)}
                </option>
              ))}
            </select>
            <select
              className={control}
              value={ruleEnabled}
              onChange={(e) => {
                setRuleEnabled(e.target.value);
                setRulePageIndex(0);
              }}
            >
              <option value="">全部状态</option>
              <option value="true">已启用</option>
              <option value="false">已停用</option>
            </select>
          </div>

          {ruleItems.length === 0 ? (
            <EmptyState
              icon={<Settings2 className="h-7 w-7" />}
              title="还没有邮件规则"
              description="没有规则就不会有自动处理：邮件不会被静默、不会被分配、也不会被自动回复。"
            />
          ) : (
            <ul className="text-copy-13 space-y-2">
              {ruleItems.map((rule) => (
                <li key={rule.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={rule.enabled ? 'success' : 'warning'}>
                      {rule.enabled ? '已启用' : '已停用'}
                    </Badge>
                    <span className="font-medium">{rule.ruleCode}</span>
                    <span className="text-muted-foreground">{rule.ruleName}</span>
                  </div>
                  <p className="text-muted-foreground text-copy-12 mt-1">{ruleText(rule)}</p>
                  {writable && (
                    <div className="mt-2 flex gap-2">
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          setEditingRule(rule);
                          setDraftCode(rule.ruleCode);
                          setDraftName(rule.ruleName);
                          setDraftOrder(String(rule.priorityOrder));
                          setDraftAction(rule.action);
                          setDraftSenderEmails((rule.senderEmails ?? []).join('\n'));
                          setDraftSenderDomains((rule.senderDomains ?? []).join(','));
                          setDraftKeywords((rule.keywords ?? []).join('\n'));
                          setDraftTagCodes((rule.ticketTagCodes ?? []).join(','));
                          setDraftMatchMode(rule.matchMode);
                          setDraftTargetType(rule.assignTargetType);
                          setDraftTargetCode(rule.assignTargetCode);
                          setDraftReplyTemplate(rule.replyTemplateCode);
                          setDraftReplySubject(rule.replySubject);
                          setDraftReplyBody(rule.replyBody);
                          setDraftMarkers((rule.importantMarkers ?? []).join('\n'));
                          setDraftEnabled(rule.enabled);
                          setDraftNote(rule.note);
                        }}
                      >
                        编辑
                      </Button>
                      <Button size="sm" variant="secondary" onClick={() => void removeRule(rule)}>
                        删除
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}

          <div className="text-muted-foreground text-copy-12">
            共 {rules?.total ?? 0} 条规则 · 第 {(rules?.page ?? 0) + 1} 页
            <Button
              size="sm"
              variant="secondary"
              className="ml-2"
              disabled={rulePageIndex <= 0}
              onClick={() => setRulePageIndex((current) => Math.max(0, current - 1))}
            >
              上一页
            </Button>
            <Button
              size="sm"
              variant="secondary"
              className="ml-1"
              disabled={!rules || (rules.page + 1) * rules.size >= rules.total}
              onClick={() => setRulePageIndex((current) => current + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 新建 / 编辑规则 */}
      {writable && (
        <SectionCard
          title={editingRule ? `编辑规则 ${editingRule.ruleCode}` : '新建邮件规则'}
          description="三个条件维度至少要有一个：全空的规则永远不会命中，却会占掉一个顺序位。规则编码建后不可改；改规则版本 +1，历史命中记录不变。"
        >
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="规则编码（建后不可改）">
                <input
                  className={control}
                  value={draftCode}
                  onChange={(e) => setDraftCode(e.target.value)}
                  placeholder="MAIL-001"
                  disabled={editingRule !== null}
                />
              </Field>
              <Field label="规则名称">
                <input
                  className={control}
                  value={draftName}
                  onChange={(e) => setDraftName(e.target.value)}
                  placeholder="VIP 客户自动分配"
                />
              </Field>
              <Field label="顺序（数字小的先命中）">
                <input
                  className={control}
                  value={draftOrder}
                  onChange={(e) => setDraftOrder(e.target.value)}
                />
              </Field>
              <Field label="命中动作">
                <select
                  className={control}
                  value={draftAction}
                  onChange={(e) => setDraftAction(e.target.value)}
                >
                  {(options?.actions ?? []).map((value) => (
                    <option key={value} value={value}>
                      {actionLabel(value)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <div className="grid gap-3 md:grid-cols-2">
              <Field label="发件人邮箱（一行一个，精确匹配）">
                <textarea
                  className={control}
                  value={draftSenderEmails}
                  onChange={(e) => setDraftSenderEmails(e.target.value)}
                  placeholder={'vip@example.com\nboss@example.com'}
                />
              </Field>
              <Field label="发件人域名（逗号分隔）">
                <input
                  className={control}
                  value={draftSenderDomains}
                  onChange={(e) => setDraftSenderDomains(e.target.value)}
                  placeholder="example.com,vip-partner.com"
                />
              </Field>
              <Field label="关键词（一行一个，主题 / 正文）">
                <textarea
                  className={control}
                  value={draftKeywords}
                  onChange={(e) => setDraftKeywords(e.target.value)}
                  placeholder={'退款\n发票'}
                />
              </Field>
              <Field label="工单标签码（逗号分隔，必须是字典里真实存在的标签）">
                <input
                  className={control}
                  value={draftTagCodes}
                  onChange={(e) => setDraftTagCodes(e.target.value)}
                  placeholder="VIP,退款"
                />
              </Field>
              <Field label="同一维度内的命中方式">
                <select
                  className={control}
                  value={draftMatchMode}
                  onChange={(e) => setDraftMatchMode(e.target.value)}
                >
                  {(options?.matchModes ?? []).map((value) => (
                    <option key={value} value={value}>
                      {matchModeLabel(value)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="重要沟通标记（一行一个，命中即视为重要沟通，不可静默）">
                <textarea
                  className={control}
                  value={draftMarkers}
                  onChange={(e) => setDraftMarkers(e.target.value)}
                  placeholder={'attorney\nchargeback'}
                />
              </Field>
            </div>

            {draftAction === 'AUTO_ASSIGN' && (
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="分配目标类型">
                  <select
                    className={control}
                    value={draftTargetType}
                    onChange={(e) => setDraftTargetType(e.target.value)}
                  >
                    {(options?.assignTargetTypes ?? []).map((value) => (
                      <option key={value} value={value}>
                        {targetTypeLabel(value)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="分配目标（组码 / 人工号）">
                  <input
                    className={control}
                    value={draftTargetCode}
                    onChange={(e) => setDraftTargetCode(e.target.value)}
                    placeholder="VIP-GROUP"
                  />
                </Field>
              </div>
            )}

            {draftAction === 'AUTO_REPLY' && (
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="回复模板码">
                  <input
                    className={control}
                    value={draftReplyTemplate}
                    onChange={(e) => setDraftReplyTemplate(e.target.value)}
                    placeholder="REPLY-001"
                  />
                </Field>
                <Field label="回复标题">
                  <input
                    className={control}
                    value={draftReplySubject}
                    onChange={(e) => setDraftReplySubject(e.target.value)}
                  />
                </Field>
                <Field label="回复正文">
                  <textarea
                    className={control}
                    value={draftReplyBody}
                    onChange={(e) => setDraftReplyBody(e.target.value)}
                  />
                </Field>
              </div>
            )}

            <div className="grid gap-3 md:grid-cols-2">
              <Field label="启用">
                <select
                  className={control}
                  value={draftEnabled ? 'true' : 'false'}
                  onChange={(e) => setDraftEnabled(e.target.value === 'true')}
                >
                  <option value="true">启用</option>
                  <option value="false">停用（不删：历史命中依据要查得到）</option>
                </select>
              </Field>
              <Field label="备注">
                <input
                  className={control}
                  value={draftNote}
                  onChange={(e) => setDraftNote(e.target.value)}
                />
              </Field>
            </div>

            <Button disabled={busy} onClick={() => void saveRule()}>
              {editingRule ? '保存修改' : '创建规则'}
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 工单标签 */}
      <SectionCard
        title="工单标签"
        description="规则的第三个条件维度引用这份字典：标签必须真实存在，否则那条规则永远不会命中。"
      >
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-2">
            <input
              className={control}
              value={tagKeyword}
              onChange={(e) => setTagKeyword(e.target.value)}
              placeholder="标签码 / 名称"
            />
            {writable && (
              <div className="grid gap-2 md:grid-cols-3">
                <input
                  className={control}
                  value={tagCode}
                  onChange={(e) => setTagCode(e.target.value)}
                  placeholder="标签码 VIP"
                />
                <input
                  className={control}
                  value={tagName}
                  onChange={(e) => setTagName(e.target.value)}
                  placeholder="名称"
                />
                <Button disabled={busy} onClick={() => void saveTag()}>
                  新建标签
                </Button>
              </div>
            )}
          </div>

          {tagItems.length === 0 ? (
            <EmptyState
              icon={<Tags className="h-7 w-7" />}
              title="还没有工单标签"
              description="标签是规则的第三个条件维度；没有标签时，规则只能靠发件人与关键词命中。"
            />
          ) : (
            <ul className="text-copy-13 space-y-2">
              {tagItems.map((tag) => (
                <li key={tag.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={tag.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {tag.status === 'ACTIVE' ? '启用' : '停用'}
                    </Badge>
                    <span className="font-medium">{tag.tagCode}</span>
                    <span className="text-muted-foreground">{tag.name}</span>
                    <span className="text-muted-foreground text-copy-12">
                      打标 {tag.usageCount} 次
                    </span>
                  </div>
                  {writable && (
                    <div className="mt-2">
                      <Button size="sm" variant="secondary" onClick={() => void removeTag(tag)}>
                        删除
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 打标与评估 */}
      <SectionCard
        title="打标与评估"
        description="先给邮件打上工单标签，再评估规则；评估会写一条只追加的命中记录。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="邮件 ID（15.1.2.1 的邮件）">
              <input
                className={control}
                value={emailId}
                onChange={(e) => setEmailId(e.target.value)}
                placeholder="邮件 uuid"
              />
            </Field>
            <Field label="给这封邮件打标签">
              <input
                className={control}
                value={tagForEmail}
                onChange={(e) => setTagForEmail(e.target.value)}
                placeholder="标签码"
                disabled={!writable}
              />
            </Field>
            <div className="flex items-end gap-2">
              <Button disabled={busy || !writable} onClick={() => void tagEmail()}>
                打标签
              </Button>
              <Button disabled={busy} onClick={() => void runEvaluate()}>
                评估规则
              </Button>
            </div>
          </div>
          {evaluateResult && (
            <p className="text-copy-13 border-border rounded border px-3 py-2">{evaluateResult}</p>
          )}
          <p className="text-muted-foreground text-copy-12">
            评估只对**收到的**邮件生效；命中后写一条命中记录（只追加），并按动作产出分配结果或自动回复意图。
          </p>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 命中记录 */}
      <SectionCard
        title="规则命中记录"
        description="🔴 命中记录只追加（改不了、删不掉）：它是「当时发生了什么」的证据。每行都显示规则声明的动作与实际生效的动作。"
      >
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-4">
            <input
              className={control}
              value={hitKeyword}
              onChange={(e) => {
                setHitKeyword(e.target.value);
                setHitPageIndex(0);
              }}
              placeholder="邮件号 / 主题 / 发件人"
            />
            <input
              className={control}
              value={hitRuleCode}
              onChange={(e) => {
                setHitRuleCode(e.target.value);
                setHitPageIndex(0);
              }}
              placeholder="规则编码"
            />
            <select
              className={control}
              value={refusedOnly ? 'true' : ''}
              onChange={(e) => {
                setRefusedOnly(e.target.value === 'true');
                setHitPageIndex(0);
              }}
            >
              <option value="">全部命中</option>
              <option value="true">只看静默被拒</option>
            </select>
            <select
              className={control}
              value={importantOnly ? 'true' : ''}
              onChange={(e) => {
                setImportantOnly(e.target.value === 'true');
                setHitPageIndex(0);
              }}
            >
              <option value="">全部沟通</option>
              <option value="true">只看重要沟通</option>
            </select>
          </div>

          {hitItems.length === 0 ? (
            <EmptyState
              icon={<ListChecks className="h-7 w-7" />}
              title={hasActiveFilter(hitParams) ? '没有符合条件的命中记录' : '还没有命中记录'}
              description={
                hasActiveFilter(hitParams)
                  ? '筛选条件太窄了：换个条件或清空筛选再看看。'
                  : '评估一封邮件之后，这里会出现命中记录（命中了哪条规则、命中了哪些条件、动作结果）。'
              }
            />
          ) : (
            <ul className="text-copy-13 space-y-2">
              {hitItems.map((hit) => (
                <li key={hit.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    {hit.silenceRefused ? (
                      <Badge tone="danger">
                        <TriangleAlert className="mr-1 h-3 w-3" />
                        静默被拒
                      </Badge>
                    ) : (
                      <Badge tone="info">{actionLabel(hit.effectiveAction)}</Badge>
                    )}
                    <span className="font-medium">{hit.ruleCode}</span>
                    <span className="text-muted-foreground">{hit.emailNo}</span>
                    <span className="text-muted-foreground text-copy-12">{hit.subject}</span>
                  </div>
                  <p className="text-copy-12 mt-1">{hitActionText(hit)}</p>
                  <p className="text-muted-foreground text-copy-12 mt-1">{hitText(hit)}</p>
                  <div className="mt-2">
                    <Button size="sm" variant="secondary" onClick={() => void openHit(hit.id)}>
                      看命中依据与规则快照
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <div className="text-muted-foreground text-copy-12">
            共 {hits?.total ?? 0} 条命中 · 第 {(hits?.page ?? 0) + 1} 页
            <Button
              size="sm"
              variant="secondary"
              className="ml-2"
              disabled={hitPageIndex <= 0}
              onClick={() => setHitPageIndex((current) => Math.max(0, current - 1))}
            >
              上一页
            </Button>
            <Button
              size="sm"
              variant="secondary"
              className="ml-1"
              disabled={!hits || (hits.page + 1) * hits.size >= hits.total}
              onClick={() => setHitPageIndex((current) => current + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 命中详情 */}
      {detail && (
        <SectionCard
          title={`命中详情 ${detail.hit.hitNo}`}
          description="命中条件 + 当时的规则快照：改规则不会追溯改变这一条。"
        >
          <div className="text-copy-13 space-y-3">
            <p>{hitActionText(detail.hit)}</p>
            <p className="text-muted-foreground text-copy-12">
              邮件 {detail.hit.emailNo} · 发件人 {detail.hit.buyerEmail} · 主题 {detail.hit.subject}
            </p>
            <p className="text-copy-12">命中依据：{matchBasisText(detail.hit.matchBasis)}</p>
            <p className="text-copy-12">当时的规则快照：{snapshotText(detail.ruleSnapshot)}</p>
            {detail.hit.important && (
              <p className="text-copy-12">
                重要沟通判定来源：{importantSourceLabel(detail.hit.importantSource)}
                {detail.hit.classificationLabel ? `（分类 ${detail.hit.classificationLabel}）` : ''}
              </p>
            )}
            {detail.assignment && (
              <p className="text-copy-12">自动分配结果：{assignmentText(detail.assignment)}</p>
            )}
            <Button size="sm" variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 自动分配结果 */}
      <SectionCard
        title="自动分配结果"
        description="分给谁、依据哪条规则、命中了哪些条件。15.4.1.1 工单未交付 ⇒ 状态恒为「等下游取」，**没有**工单 id。"
      >
        {assignmentItems.length === 0 ? (
          <EmptyState
            icon={<FileWarning className="h-7 w-7" />}
            title="还没有自动分配结果"
            description="命中「自动分配」规则的邮件会在这里留下一条待下游取走的分配结果。"
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {assignmentItems.map((assignment) => (
              <li key={assignment.id} className="border-border rounded-md px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={assignment.status === 'CANCELLED' ? 'warning' : 'info'}>
                    {assignment.status === 'PENDING_DISPATCH'
                      ? '等下游取'
                      : assignment.status === 'DISPATCHED'
                        ? '已投递'
                        : '已取消'}
                  </Badge>
                  <span className="font-medium">{assignment.assignmentNo}</span>
                  <span className="text-muted-foreground">{assignment.emailNo}</span>
                </div>
                <p className="text-muted-foreground text-copy-12 mt-1">
                  {assignmentText(assignment)}
                </p>
                {writable && assignment.status === 'PENDING_DISPATCH' && (
                  <div className="mt-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => void cancelAssignment(assignment)}
                    >
                      取消分配（写理由）
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 统计 */}
      <SectionCard
        title="规则命中统计"
        description="静默被拒的次数越多，说明静默规则与重要沟通的冲突越多，规则该改了。"
      >
        <div className="text-copy-13 space-y-2">
          <p>{statisticsText(statistics)}</p>
          <ul className="text-muted-foreground text-copy-12 space-y-1">
            {(statistics?.byRule ?? []).map((stat) => (
              <li key={stat.ruleId}>{ruleStatText(stat)}</li>
            ))}
          </ul>
          {statistics?.note && <p className="text-copy-12">{statistics.note}</p>}
        </div>
      </SectionCard>

      <p className="text-muted-foreground text-copy-12">
        <BellOff className="mr-1 inline h-3 w-3" />
        静默只对**普通**沟通生效；重要沟通命中静默规则会被服务端强制降级为「不静默 + 转人工」，
        页面不提供任何绕过这条降级的入口。
      </p>
    </div>
  );
}

function errorText(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return fallback;
}
