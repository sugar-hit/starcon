'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import {
  Brain,
  FileText,
  ListChecks,
  RefreshCw,
  Search,
  Settings2,
  Tags,
  TriangleAlert,
} from 'lucide-react';
import type {
  CrmMessageClassificationCapabilities,
  CrmMessageClassificationClassifyResult,
  CrmMessageClassificationDetail,
  CrmMessageClassificationOptions,
  CrmMessageClassificationPage,
  CrmMessageClassificationRulePage,
  CrmMessageClassificationRuleRow,
  CrmMessageClassificationStatistics,
  CrmMessageClassificationTrainingSamplePage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  classifyMessage,
  classificationText,
  correctClassification,
  createClassificationRule,
  deleteClassificationRule,
  fetchClassificationCapabilities,
  fetchClassificationOptions,
  fetchClassificationRules,
  fetchClassifications,
  fetchClassificationStatistics,
  fetchMessageClassification,
  fetchTrainingSamples,
  hasActiveFilter,
  labelLabel,
  matchBasisText,
  matchModeLabel,
  parseCodeDraft,
  parseKeywordDraft,
  priorityLabel,
  priorityText,
  ruleStatText,
  ruleText,
  statisticsText,
  summaryText,
  trainingSampleText,
  updateClassificationRule,
  type ClassificationParams,
  type RuleParams,
  type SampleParams,
} from './message-classification-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.1.1.3 消息分类工作区。
 *
 * <p>页面按「先看清边界、再配规则、再分类、再纠正、最后看回流」的顺序排，因为
 * 蓝图加粗句是「**人工纠正要回流为训练样本**，否则分类准确率永远上不去」。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       15.5.1 自动回复与 1.3 预警两维是 available=false，页面把它们<b>显式灰掉</b>
 *       并写清归属模块，<b>不提供</b>任何看起来能点的假入口。</li>
 *   <li><b>分类规则</b> —— 关键词 + 适用平台 + 适用消息类型 + 显式顺序 + 启用状态，
 *       全部真配真存；<b>关键词必填</b>（没有关键词的规则永远不会命中，
 *       却会占掉一个顺序位）。</li>
 *   <li><b>分类结论</b> —— 真分页真筛选；每行都显示
 *       <b>建议值 / 人工值 / 生效值 / 是否冲突</b>，不只显示一个数字。</li>
 *   <li><b>详情</b> —— 结论 + 命中依据（命中了哪条规则的哪个关键词）+ 消息正文 +
 *       纠正历史；<b>人工纠正</b>表单写清「纠正只追加、不改写结论」。</li>
 *   <li><b>训练样本</b> —— 机器预测 + 人工纠正 + <b>消息正文</b>三件套；
 *       少了正文就只剩标签对，训不出「哪句话被判成了什么」。</li>
 *   <li><b>规则命中统计</b> —— 每条规则命中多少次、被纠正多少次、标签分歧多少次。</li>
 * </ul>
 */
export function MessageClassificationWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.classification.update');

  const [capabilities, setCapabilities] = useState<CrmMessageClassificationCapabilities | null>(
    null,
  );
  const [options, setOptions] = useState<CrmMessageClassificationOptions | null>(null);
  const [rules, setRules] = useState<CrmMessageClassificationRulePage | null>(null);
  const [page, setPage] = useState<CrmMessageClassificationPage | null>(null);
  const [detail, setDetail] = useState<CrmMessageClassificationDetail | null>(null);
  const [samples, setSamples] = useState<CrmMessageClassificationTrainingSamplePage | null>(null);
  const [statistics, setStatistics] = useState<CrmMessageClassificationStatistics | null>(null);
  const [classifyResult, setClassifyResult] =
    useState<CrmMessageClassificationClassifyResult | null>(null);

  // 结论筛选
  const [label, setLabel] = useState('');
  const [suggestedPriority, setSuggestedPriority] = useState('');
  const [conflictOnly, setConflictOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 规则筛选与草稿
  const [ruleKeyword, setRuleKeyword] = useState('');
  const [ruleEnabled, setRuleEnabled] = useState('');
  const [rulePageIndex, setRulePageIndex] = useState(0);
  const [editingRule, setEditingRule] = useState<CrmMessageClassificationRuleRow | null>(null);
  const [draftCode, setDraftCode] = useState('');
  const [draftName, setDraftName] = useState('');
  const [draftOrder, setDraftOrder] = useState('100');
  const [draftLabel, setDraftLabel] = useState('LOGISTICS');
  const [draftPriority, setDraftPriority] = useState('NORMAL');
  const [draftAutoReply, setDraftAutoReply] = useState(false);
  const [draftKeywords, setDraftKeywords] = useState('');
  const [draftMatchMode, setDraftMatchMode] = useState('ANY');
  const [draftPlatforms, setDraftPlatforms] = useState('');
  const [draftTypes, setDraftTypes] = useState('');
  const [draftEnabled, setDraftEnabled] = useState(true);
  const [draftNote, setDraftNote] = useState('');

  // 分类与纠正
  const [classifyMessageId, setClassifyMessageId] = useState('');
  const [correctLabel, setCorrectLabel] = useState('LOGISTICS');
  const [correctPriority, setCorrectPriority] = useState('NORMAL');
  const [correctAutoReply, setCorrectAutoReply] = useState(false);
  const [correctReason, setCorrectReason] = useState('');

  // 训练样本筛选
  const [sampleKeyword, setSampleKeyword] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const params = useMemo<ClassificationParams>(
    () => ({
      label: label || undefined,
      suggestedPriority: suggestedPriority || undefined,
      priorityConflict: conflictOnly ? true : undefined,
      keyword: keyword || undefined,
      page: pageIndex,
      size: 20,
    }),
    [label, suggestedPriority, conflictOnly, keyword, pageIndex],
  );

  const ruleParams = useMemo<RuleParams>(
    () => ({
      keyword: ruleKeyword || undefined,
      enabled: ruleEnabled === '' ? undefined : ruleEnabled === 'true',
      page: rulePageIndex,
      size: 20,
    }),
    [ruleKeyword, ruleEnabled, rulePageIndex],
  );

  const sampleParams = useMemo<SampleParams>(
    () => ({
      keyword: sampleKeyword || undefined,
      page: 0,
      size: 20,
    }),
    [sampleKeyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, opts, ruleList, result, sampleList, stats] = await Promise.all([
        fetchClassificationCapabilities(),
        fetchClassificationOptions(),
        fetchClassificationRules(ruleParams),
        fetchClassifications(params),
        fetchTrainingSamples(sampleParams),
        fetchClassificationStatistics(),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setRules(ruleList);
      setPage(result);
      setSamples(sampleList);
      setStatistics(stats);
    } catch (e) {
      setError(errorText(e, '消息分类加载失败'));
    } finally {
      setLoading(false);
    }
  }, [params, ruleParams, sampleParams]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (messageId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchMessageClassification(messageId));
    } catch (e) {
      setActionError(errorText(e, '分类详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const keywords = parseKeywordDraft(draftKeywords);
      if (keywords.length === 0) {
        setActionError(
          '规则必须有至少一个关键词：一条没有关键词的规则永远不会命中，却会占掉一个顺序位 —— ' +
            '那是一条看起来配了、其实没生效的规则。',
        );
        return;
      }
      const payload = {
        ruleCode: draftCode.trim(),
        ruleName: draftName.trim() || draftCode.trim(),
        priorityOrder: Number(draftOrder) || 0,
        targetLabel: draftLabel,
        suggestedPriority: draftPriority,
        autoReplyEligible: draftAutoReply,
        keywords,
        matchMode: draftMatchMode,
        platformCodes: parseCodeDraft(draftPlatforms),
        messageTypes: parseCodeDraft(draftTypes),
        enabled: draftEnabled,
        note: draftNote,
      };
      const result = editingRule
        ? await updateClassificationRule(editingRule.id, payload)
        : await createClassificationRule(payload);
      setNotice(`${result.note}（规则 ${result.rule.ruleCode}）`);
      setEditingRule(null);
      setDraftCode('');
      setDraftName('');
      setDraftKeywords('');
      setDraftNote('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '规则保存失败'));
    } finally {
      setBusy(false);
    }
  }, [
    draftCode,
    draftName,
    draftOrder,
    draftLabel,
    draftPriority,
    draftAutoReply,
    draftKeywords,
    draftMatchMode,
    draftPlatforms,
    draftTypes,
    draftEnabled,
    draftNote,
    editingRule,
    load,
  ]);

  const removeRule = useCallback(
    async (rule: CrmMessageClassificationRuleRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const result = await deleteClassificationRule(rule.id);
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

  const runClassify = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    setClassifyResult(null);
    try {
      const result = await classifyMessage(classifyMessageId.trim());
      setClassifyResult(result);
      setNotice(result.reason);
      await load();
    } catch (e) {
      setActionError(errorText(e, '分类失败'));
    } finally {
      setBusy(false);
    }
  }, [classifyMessageId, load]);

  const submitCorrection = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await correctClassification({
        messageId: detail.classification.messageId,
        correctedLabel: correctLabel,
        correctedPriority: correctPriority,
        correctedAutoReplyEligible: correctAutoReply,
        reason: correctReason,
      });
      setNotice(result.note);
      setCorrectReason('');
      await openDetail(detail.classification.messageId);
      await load();
    } catch (e) {
      setActionError(errorText(e, '人工纠正失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, correctLabel, correctPriority, correctAutoReply, correctReason, openDetail, load]);

  if (loading) {
    return (
      <SectionCard title="消息分类">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  const items = page?.items ?? [];
  const filtered = hasActiveFilter(params);
  const ruleItems = rules?.items ?? [];

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-copy-13 rounded-md px-3 py-2">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 能力声明 */}
      <SectionCard title="能力声明">
        <ul className="text-copy-13 space-y-2">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone={dimension.available ? 'success' : 'warning'}>
                {dimension.available ? '可用' : '未接入'}
              </Badge>
              <span className="font-medium">{dimension.description}</span>
              {!dimension.available && (
                <span className="text-muted-foreground">
                  （归属模块 {dimension.ownerModule || '—'}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground text-copy-12 mt-3">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 分类规则 */}
      <SectionCard
        title="分类规则"
        description="关键词 + 适用平台 + 适用消息类型 + 显式顺序 + 启用状态，全部真配真存。"
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
            <select
              className={control}
              value={draftLabel}
              onChange={(e) => setDraftLabel(e.target.value)}
              disabled={!writable}
            >
              {(options?.ruleLabels ?? []).map((value) => (
                <option key={value} value={value}>
                  新建规则的标签：{labelLabel(value)}
                </option>
              ))}
            </select>
          </div>

          {ruleItems.length === 0 ? (
            <EmptyState
              icon={<Settings2 className="h-7 w-7" />}
              title="还没有分类规则"
              description="没有规则就不会有分类结论：消息会被记为「未分类」，且不建议自动回复。"
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
                          setDraftLabel(rule.targetLabel);
                          setDraftPriority(rule.suggestedPriority);
                          setDraftAutoReply(rule.autoReplyEligible);
                          setDraftKeywords((rule.keywords ?? []).join('\n'));
                          setDraftMatchMode(rule.matchMode);
                          setDraftPlatforms((rule.platformCodes ?? []).join(','));
                          setDraftTypes((rule.messageTypes ?? []).join(','));
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
          title={editingRule ? `编辑规则 ${editingRule.ruleCode}` : '新建分类规则'}
          description="关键词必填：一条没有关键词的规则永远不会命中，却会占掉一个顺序位。规则编码建后不可改。"
        >
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="规则编码（建后不可改）">
                <input
                  className={control}
                  value={draftCode}
                  onChange={(e) => setDraftCode(e.target.value)}
                  placeholder="LOG-001"
                  disabled={!writable || editingRule !== null}
                />
              </Field>
              <Field label="规则名称">
                <input
                  className={control}
                  value={draftName}
                  onChange={(e) => setDraftName(e.target.value)}
                  placeholder="物流查询关键词"
                  disabled={!writable}
                />
              </Field>
              <Field label="顺序（数字小的先命中）">
                <input
                  className={control}
                  value={draftOrder}
                  onChange={(e) => setDraftOrder(e.target.value)}
                  placeholder="10"
                  disabled={!writable}
                />
              </Field>
              <Field label="目标标签">
                <select
                  className={control}
                  value={draftLabel}
                  onChange={(e) => setDraftLabel(e.target.value)}
                  disabled={!writable}
                >
                  {(options?.ruleLabels ?? []).map((value) => (
                    <option key={value} value={value}>
                      {labelLabel(value)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="建议优先级">
                <select
                  className={control}
                  value={draftPriority}
                  onChange={(e) => setDraftPriority(e.target.value)}
                  disabled={!writable}
                >
                  {(options?.priorities ?? []).map((value) => (
                    <option key={value} value={value}>
                      {priorityLabel(value)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="命中方式">
                <select
                  className={control}
                  value={draftMatchMode}
                  onChange={(e) => setDraftMatchMode(e.target.value)}
                  disabled={!writable}
                >
                  {(options?.matchModes ?? []).map((value) => (
                    <option key={value} value={value}>
                      {matchModeLabel(value)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field label="关键词（一行一个，必填非空）">
              <textarea
                className={control}
                rows={3}
                value={draftKeywords}
                onChange={(e) => setDraftKeywords(e.target.value)}
                placeholder={'tracking\n运单\n物流'}
                disabled={!writable}
              />
            </Field>
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="适用平台（逗号分隔，留空 = 全部平台）">
                <input
                  className={control}
                  value={draftPlatforms}
                  onChange={(e) => setDraftPlatforms(e.target.value)}
                  placeholder="AMAZON,SHOPIFY"
                  disabled={!writable}
                />
              </Field>
              <Field label="适用消息类型（逗号分隔，留空 = 全部类型）">
                <input
                  className={control}
                  value={draftTypes}
                  onChange={(e) => setDraftTypes(e.target.value)}
                  placeholder="BUYER_MESSAGE,PLATFORM_INBOX"
                  disabled={!writable}
                />
              </Field>
            </div>
            <div className="flex flex-wrap items-center gap-4">
              <label className="text-copy-13 flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={draftAutoReply}
                  onChange={(e) => setDraftAutoReply(e.target.checked)}
                  disabled={!writable}
                />
                命中后可自动回复（<strong>判定结果</strong>；15.5.1 自动回复未交付，本页不触发回复）
              </label>
              <label className="text-copy-13 flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={draftEnabled}
                  onChange={(e) => setDraftEnabled(e.target.checked)}
                  disabled={!writable}
                />
                启用（停用而不是删除：历史命中依据要查得到规则）
              </label>
            </div>
            <Field label="备注">
              <input
                className={control}
                value={draftNote}
                onChange={(e) => setDraftNote(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <div className="flex gap-2">
              <Button
                onClick={() => void submitRule()}
                disabled={busy || !writable || draftCode.trim() === ''}
                size="sm"
              >
                <Tags className="mr-1 h-4 w-4" />
                {editingRule ? '保存修改' : '创建规则'}
              </Button>
              {editingRule && (
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => {
                    setEditingRule(null);
                    setDraftCode('');
                    setDraftName('');
                    setDraftKeywords('');
                  }}
                >
                  取消编辑
                </Button>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 发起分类 */}
      {writable && (
        <SectionCard
          title="发起分类"
          description="消息来自 15.1.1.1 消息收件箱（/crm/messages）；这里填它的消息 id。"
        >
          <div className="space-y-3">
            <Field
              label="消息 id"
              hint="分类只写结论，不会改这条消息的人工优先级；「是否可自动回复」只是判定结果。"
            >
              <input
                className={control}
                value={classifyMessageId}
                onChange={(e) => setClassifyMessageId(e.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
                disabled={!writable}
              />
            </Field>
            <Button
              onClick={() => void runClassify()}
              disabled={busy || !writable || classifyMessageId.trim() === ''}
              size="sm"
            >
              <Brain className="mr-1 h-4 w-4" />
              分类这条消息
            </Button>
            {classifyResult && (
              <div className="border-border text-copy-13 rounded-md px-3 py-2">
                <p>
                  结论：
                  <strong>{classifyResult.label ? labelLabel(classifyResult.label) : '—'}</strong>
                  {classifyResult.classificationNo ? `（${classifyResult.classificationNo}）` : ''}
                </p>
                {classifyResult.suggestedPriority && (
                  <p>
                    {priorityText({
                      suggestedPriority: classifyResult.suggestedPriority,
                      manualPriority: classifyResult.manualPriority ?? 'NORMAL',
                      effectivePriority: classifyResult.effectivePriority ?? 'NORMAL',
                      priorityConflict: classifyResult.priorityConflict ?? false,
                    })}
                  </p>
                )}
                <p className="text-muted-foreground">{classifyResult.reason}</p>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 分类结论 */}
      <SectionCard
        title="分类结论"
        description={summaryText(page?.summary)}
        extra={
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-4">
            <select
              className={control}
              value={label}
              onChange={(e) => {
                setLabel(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部标签</option>
              {(options?.labels ?? []).map((value) => (
                <option key={value} value={value}>
                  {labelLabel(value)}
                </option>
              ))}
            </select>
            <select
              className={control}
              value={suggestedPriority}
              onChange={(e) => {
                setSuggestedPriority(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部建议优先级</option>
              {(options?.priorities ?? []).map((value) => (
                <option key={value} value={value}>
                  {priorityLabel(value)}
                </option>
              ))}
            </select>
            <label className="text-copy-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={conflictOnly}
                onChange={(e) => {
                  setConflictOnly(e.target.checked);
                  setPageIndex(0);
                }}
              />
              只看人工与建议冲突的
            </label>
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
              placeholder="关键字（主题 / 正文 / 消息号 / 命中规则）"
            />
          </div>

          {items.length === 0 ? (
            <EmptyState
              icon={filtered ? <Search className="h-7 w-7" /> : <ListChecks className="h-7 w-7" />}
              title={filtered ? '当前筛选条件下没有分类结论' : '还没有分类过任何消息'}
              description={
                filtered
                  ? '把筛选条件放宽一点再试 —— 这不代表没有分类过。'
                  : '先在上面「发起分类」里给一条消息跑一次分类，结论与命中依据就会出现在这里。'
              }
            />
          ) : (
            <ul className="text-copy-13 space-y-2">
              {items.map((row) => (
                <li key={row.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={row.label === 'SPAM' ? 'warning' : 'success'}>
                      {labelLabel(row.label)}
                    </Badge>
                    {row.priorityConflict && (
                      <Badge tone="warning">
                        <TriangleAlert className="mr-1 h-3 w-3" />
                        人工与建议冲突
                      </Badge>
                    )}
                    <span className="font-medium">{row.subject || row.messageNo}</span>
                    <span className="text-muted-foreground">{row.classificationNo}</span>
                  </div>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    {classificationText(row)}
                  </p>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    消息 {row.messageNo} · 买家 {row.buyerReference} · 订单 {row.orderReference} ·
                    分类时刻 {row.classifiedAt}
                  </p>
                  <Button
                    size="sm"
                    variant="secondary"
                    className="mt-2"
                    onClick={() => void openDetail(row.messageId)}
                  >
                    查看结论与命中依据
                  </Button>
                </li>
              ))}
            </ul>
          )}

          <div className="text-muted-foreground text-copy-12 flex flex-wrap items-center gap-2">
            <span>
              共 {page?.total ?? 0} 条 · 第 {(page?.page ?? 0) + 1} 页 · 每页 {page?.size ?? 0} 条
            </span>
            <Button
              size="sm"
              variant="secondary"
              disabled={pageIndex <= 0}
              onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
            >
              上一页
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={!page || (page.page + 1) * page.size >= page.total}
              onClick={() => setPageIndex((current) => current + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 详情与纠正 */}
      {detail && (
        <SectionCard
          title="分类详情"
          description={`${detail.classification.classificationNo} · 消息 ${detail.message.messageNo}`}
        >
          <div className="text-copy-13 space-y-3">
            <div className="border-border rounded-md px-3 py-2">
              <p className="font-medium">结论</p>
              <p className="text-muted-foreground mt-1">
                {classificationText(detail.classification)}
              </p>
              <p className="text-muted-foreground mt-1">
                {matchBasisText(detail.classification.matchBasis)}
              </p>
              <p className="text-muted-foreground mt-1">
                分类器口径 {detail.classification.classifierVersion} · 分类时刻{' '}
                {detail.classification.classifiedAt}
              </p>
            </div>

            <div className="border-border rounded-md px-3 py-2">
              <p className="font-medium">消息正文</p>
              <p className="text-muted-foreground mt-1">
                主题：{detail.message.subject || '（无主题）'} · 平台 {detail.message.platformCode}{' '}
                · 类型 {detail.message.messageType} · 人工优先级{' '}
                {priorityLabel(detail.message.manualPriority)}
              </p>
              <p className="text-copy-12 mt-2 whitespace-pre-wrap">{detail.message.body}</p>
            </div>

            <div className="border-border rounded-md px-3 py-2">
              <p className="font-medium">纠正历史（只追加，不可改不可删）</p>
              {detail.feedback.length === 0 ? (
                <p className="text-muted-foreground mt-1">
                  还没有人工纠正。纠正会记下「机器当时判成了什么」与「人改成了什么」，并回流成训练样本。
                </p>
              ) : (
                <ul className="mt-1 space-y-1">
                  {detail.feedback.map((item) => (
                    <li key={item.id}>
                      机器判 {labelLabel(item.predictedLabel)} → 人工改成{' '}
                      {labelLabel(item.correctedLabel)} · 理由：{item.reason} · {item.correctedAt}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {writable && (
              <div className="border-border rounded-md px-3 py-2">
                <p className="font-medium">人工纠正</p>
                <p className="text-muted-foreground mt-1">
                  纠正<strong>只追加</strong>：分类结论本身不会被改写（机器判成什么仍然查得到）。
                  理由必填 —— 没有理由的纠正没法复核，也没法当训练样本用。
                </p>
                <div className="mt-2 grid gap-3 md:grid-cols-3">
                  <Field label="人工判定的标签">
                    <select
                      className={control}
                      value={correctLabel}
                      onChange={(e) => setCorrectLabel(e.target.value)}
                      disabled={!writable}
                    >
                      {(options?.labels ?? []).map((value) => (
                        <option key={value} value={value}>
                          {labelLabel(value)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="人工判定的优先级">
                    <select
                      className={control}
                      value={correctPriority}
                      onChange={(e) => setCorrectPriority(e.target.value)}
                      disabled={!writable}
                    >
                      {(options?.priorities ?? []).map((value) => (
                        <option key={value} value={value}>
                          {priorityLabel(value)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="可自动回复">
                    <label className="text-copy-13 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={correctAutoReply}
                        onChange={(e) => setCorrectAutoReply(e.target.checked)}
                        disabled={!writable}
                      />
                      人工判定后可自动回复
                    </label>
                  </Field>
                </div>
                <Field label="纠正理由（必填）">
                  <input
                    className={control}
                    value={correctReason}
                    onChange={(e) => setCorrectReason(e.target.value)}
                    placeholder="买家其实要退货，不是问物流"
                    disabled={!writable}
                  />
                </Field>
                <Button
                  className="mt-2"
                  size="sm"
                  onClick={() => void submitCorrection()}
                  disabled={busy || !writable || correctReason.trim() === ''}
                >
                  记录纠正
                </Button>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 训练样本 */}
      <SectionCard
        title="训练样本（人工纠正回流）"
        description="机器预测 + 人工纠正 + 消息正文三件套 —— 少了正文就只剩标签对，训不出「哪句话被判成了什么」。"
      >
        <div className="space-y-3">
          <input
            className={control}
            value={sampleKeyword}
            onChange={(e) => setSampleKeyword(e.target.value)}
            placeholder="关键字（主题 / 正文 / 消息号）"
          />
          {(samples?.items ?? []).length === 0 ? (
            <EmptyState
              icon={<FileText className="h-7 w-7" />}
              title="还没有训练样本"
              description="人工纠正一次就会回流成一条训练样本：机器当时的判断与人工的判断都会留在里面。"
            />
          ) : (
            <ul className="text-copy-13 space-y-2">
              {(samples?.items ?? []).map((sample) => (
                <li key={sample.feedbackId} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={sample.labelAgreed ? 'success' : 'warning'}>
                      {sample.labelAgreed ? '机器判对了' : '机器判错了'}
                    </Badge>
                    <span className="font-medium">{sample.subject || sample.messageNo}</span>
                  </div>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    {trainingSampleText(sample)}
                  </p>
                  <p className="text-muted-foreground text-copy-12 mt-1">理由：{sample.reason}</p>
                  <p className="text-copy-12 mt-1 whitespace-pre-wrap">{sample.body}</p>
                </li>
              ))}
            </ul>
          )}
          <div className="text-muted-foreground text-copy-12">
            共 {samples?.total ?? 0} 条训练样本
          </div>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 规则命中统计 */}
      <SectionCard title="规则命中统计" description={statisticsText(statistics)}>
        {(statistics?.byRule ?? []).length === 0 ? (
          <p className="text-muted-foreground text-copy-13">
            还没有命中统计：先配规则、再分类，这里才会出现每条规则的命中次数与被纠正次数。
          </p>
        ) : (
          <ul className="text-copy-13 space-y-1">
            {(statistics?.byRule ?? []).map((stat) => (
              <li key={stat.ruleId} className="border-border rounded-md px-3 py-2">
                {ruleStatText(stat)}
              </li>
            ))}
          </ul>
        )}
        {statistics?.note && (
          <p className="text-muted-foreground text-copy-12 mt-3">{statistics.note}</p>
        )}
      </SectionCard>
    </div>
  );
}

/** 从错误里取人话。 */
function errorText(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return fallback;
}
