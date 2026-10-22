'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, Segmented, StatCard } from '@starcon/ui';
import { AlertTriangle, Play, RefreshCw, Tag, Tags, Trash2, UserCheck } from 'lucide-react';
import type {
  CrmCustomerTagAssignmentPage,
  CrmCustomerTagAssignmentRow,
  CrmCustomerTagCapabilities,
  CrmCustomerTagCustomerCandidatePage,
  CrmCustomerTagDistribution,
  CrmCustomerTagPage,
  CrmCustomerTagRulePage,
  CrmCustomerTagRuleRow,
  CrmCustomerTagRunPage,
  CrmCustomerTagRunRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  createCustomerTag,
  createCustomerTagAssignment,
  createCustomerTagRule,
  customerTagCategoryLabel,
  customerTagEvidence,
  customerTagEvidenceSummary,
  customerTagMetricLabel,
  customerTagOutcomeLabel,
  customerTagShareText,
  customerTagSourceLabel,
  customerTagStateLabel,
  deleteCustomerTag,
  deleteCustomerTagRule,
  fetchCustomerTagAssignments,
  fetchCustomerTagCapabilities,
  fetchCustomerTagCustomerCandidates,
  fetchCustomerTagDistribution,
  fetchCustomerTagEvaluations,
  fetchCustomerTagRules,
  fetchCustomerTagRuns,
  fetchCustomerTags,
  reviewCustomerTagAssignment,
  runCustomerTagRule,
} from './customer-tag-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.3.2.1 客户标签工作区。
 *
 * <p>页面按「先看清边界、再看标签、最后才动手」的顺序排，因为蓝图加粗句是
 * 「**"退货率高"标签要谨慎使用**，避免对正常买家产生歧视性动作」——
 * 界面上第一件要让人看到的就是**能力边界**与**判定口径**，
 * 而不是一张可以直接点「批量打标」的按钮。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       客户筛选（15.3.1.1）、促销定向（14.4）、关怀邮件（15.5.2.1）三块 available=false，
 *       页面把它们**显式灰掉**并写清归属模块，
 *       <b>不提供</b>「按标签发促销」「按标签发关怀邮件」这类假入口。</li>
 *   <li><b>标签定义</b> —— 标签体系本身（VIP / 复购 / 退货率高 / 高客单 / 风险 / 渠道来源）。
 *       可争议标签（退货率高、风险）带「必须人工复核」开关，
 *       打开之后打这枚标签**必须**写原因。</li>
 *   <li><b>打标规则</b> —— 判定口径 + 阈值 + 最低样本量 + 基线倍率。
 *       「跑一次」之后能看到：命中多少、幂等多少、<b>不可判定多少</b>、
 *       租户基线是多少、上游扫描有没有被截断。</li>
 *   <li><b>标签记录</b> —— 客户 × 标签。**人工与自动分开显示**，
 *       每条自动标签都能展开看「依据哪几笔订单 / 哪几张售后单」；
 *       可争议标签可人工确认 / 撤销（撤销必须写原因）。</li>
 *   <li><b>分布统计</b> —— 真算（服务端聚合），占比由服务端算好，
 *       分母为 0 时显示「不计算」而不是 0%。</li>
 * </ul>
 */
export function CustomerTagWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.tag.update');

  const [view, setView] = useState<'tags' | 'rules' | 'assignments' | 'distribution'>('tags');

  const [capabilities, setCapabilities] = useState<CrmCustomerTagCapabilities | null>(null);
  const [tags, setTags] = useState<CrmCustomerTagPage | null>(null);
  const [rules, setRules] = useState<CrmCustomerTagRulePage | null>(null);
  const [runs, setRuns] = useState<CrmCustomerTagRunPage | null>(null);
  const [assignments, setAssignments] = useState<CrmCustomerTagAssignmentPage | null>(null);
  const [distribution, setDistribution] = useState<CrmCustomerTagDistribution | null>(null);
  const [candidates, setCandidates] = useState<CrmCustomerTagCustomerCandidatePage | null>(null);

  // 标签定义筛选
  const [tagCategory, setTagCategory] = useState('');
  const [tagStatus, setTagStatus] = useState('');
  const [tagKeyword, setTagKeyword] = useState('');
  const [tagPageIndex, setTagPageIndex] = useState(0);

  // 新建标签定义
  const [newTagCode, setNewTagCode] = useState('');
  const [newTagName, setNewTagName] = useState('');
  const [newTagCategory, setNewTagCategory] = useState('VALUE');
  const [newTagDescription, setNewTagDescription] = useState('');
  const [newTagContestable, setNewTagContestable] = useState(false);
  const [newTagReviewRequired, setNewTagReviewRequired] = useState(false);

  // 规则筛选与新建
  const [ruleMetric, setRuleMetric] = useState('');
  const [ruleStatus, setRuleStatus] = useState('');
  const [rulePageIndex, setRulePageIndex] = useState(0);
  const [newRuleCode, setNewRuleCode] = useState('');
  const [newRuleName, setNewRuleName] = useState('');
  const [newRuleTagId, setNewRuleTagId] = useState('');
  const [newRuleMetric, setNewRuleMetric] = useState('ORDER_COUNT');
  const [newRuleThreshold, setNewRuleThreshold] = useState('');
  const [newRuleCurrency, setNewRuleCurrency] = useState('USD');
  const [newRuleCondition, setNewRuleCondition] = useState('');
  const [newRuleMinSample, setNewRuleMinSample] = useState('5');
  const [newRuleWindowDays, setNewRuleWindowDays] = useState('365');
  const [newRuleBaseline, setNewRuleBaseline] = useState('2');

  // 标签记录筛选
  const [assignmentTagId, setAssignmentTagId] = useState('');
  const [assignmentSource, setAssignmentSource] = useState('');
  const [assignmentState, setAssignmentState] = useState('');
  const [assignmentKeyword, setAssignmentKeyword] = useState('');
  const [assignmentPageIndex, setAssignmentPageIndex] = useState(0);

  // 手工打标
  const [manualCustomerRef, setManualCustomerRef] = useState('');
  const [manualCustomerName, setManualCustomerName] = useState('');
  const [manualTagId, setManualTagId] = useState('');
  const [manualReason, setManualReason] = useState('');
  const [candidateKeyword, setCandidateKeyword] = useState('');

  // 复核
  const [reviewReason, setReviewReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, tagPage, rulePage, runPage, assignmentPage, stats] = await Promise.all([
        fetchCustomerTagCapabilities(),
        fetchCustomerTags({
          category: tagCategory || undefined,
          status: tagStatus || undefined,
          keyword: tagKeyword || undefined,
          page: tagPageIndex,
          size: 20,
        }),
        fetchCustomerTagRules({
          metric: ruleMetric || undefined,
          status: ruleStatus || undefined,
          page: rulePageIndex,
          size: 20,
        }),
        fetchCustomerTagRuns({ page: 0, size: 10 }),
        fetchCustomerTagAssignments({
          tagId: assignmentTagId || undefined,
          source: assignmentSource || undefined,
          state: assignmentState || undefined,
          keyword: assignmentKeyword || undefined,
          page: assignmentPageIndex,
          size: 20,
        }),
        fetchCustomerTagDistribution(),
      ]);
      setCapabilities(caps);
      setTags(tagPage);
      setRules(rulePage);
      setRuns(runPage);
      setAssignments(assignmentPage);
      setDistribution(stats);
    } catch (e) {
      setError(errorText(e, '客户标签工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [
    tagCategory,
    tagStatus,
    tagKeyword,
    tagPageIndex,
    ruleMetric,
    ruleStatus,
    rulePageIndex,
    assignmentTagId,
    assignmentSource,
    assignmentState,
    assignmentKeyword,
    assignmentPageIndex,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 候选客户：手工打标时「选谁」的入口（数据来自上游订单事实的真实聚合）。 */
  const loadCandidates = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const page = await fetchCustomerTagCustomerCandidates({
        keyword: candidateKeyword || undefined,
        size: 20,
      });
      setCandidates(page);
    } catch (e) {
      setActionError(errorText(e, '候选客户读不到（上游订单事实不可用）'));
    } finally {
      setBusy(false);
    }
  }, [candidateKeyword]);

  const submitTag = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createCustomerTag({
        tagCode: newTagCode.trim(),
        name: newTagName.trim(),
        category: newTagCategory,
        description: newTagDescription.trim(),
        color: '',
        status: 'ACTIVE',
        contestable: newTagContestable,
        reviewRequired: newTagReviewRequired,
      });
      setNotice(`已创建标签 ${newTagCode.trim()}。`);
      setNewTagCode('');
      setNewTagName('');
      setNewTagDescription('');
      setNewTagContestable(false);
      setNewTagReviewRequired(false);
      await load();
    } catch (e) {
      setActionError(errorText(e, '创建标签失败'));
    } finally {
      setBusy(false);
    }
  }, [
    newTagCode,
    newTagName,
    newTagCategory,
    newTagDescription,
    newTagContestable,
    newTagReviewRequired,
    load,
  ]);

  const removeTag = useCallback(
    async (tagId: string, tagCode: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteCustomerTag(tagId);
        setNotice(`标签 ${tagCode} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '删标签失败（被规则或标签记录引用时删不掉）'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createCustomerTagRule({
        ruleCode: newRuleCode.trim(),
        name: newRuleName.trim(),
        tagId: newRuleTagId,
        metric: newRuleMetric,
        // ⚠️ 只做**格式转换**（输入串 → 契约里的 number），**不做任何前端算术**：
        //   阈值原样交给后端落 numeric(19,6)，页面不参与计算。
        threshold: newRuleThreshold === '' ? null : Number(newRuleThreshold),
        currency: newRuleMetric === 'HIGH_VALUE' ? newRuleCurrency.trim() : null,
        conditionValue: newRuleMetric === 'CHANNEL_SOURCE' ? newRuleCondition.trim() : null,
        minSample: Number(newRuleMinSample),
        windowDays: Number(newRuleWindowDays),
        baselineMultiplier: Number(newRuleBaseline),
        status: 'ACTIVE',
        note: '',
      });
      setNotice(`已创建规则 ${newRuleCode.trim()}。`);
      setNewRuleCode('');
      setNewRuleName('');
      setNewRuleThreshold('');
      setNewRuleCondition('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '创建规则失败'));
    } finally {
      setBusy(false);
    }
  }, [
    newRuleCode,
    newRuleName,
    newRuleTagId,
    newRuleMetric,
    newRuleThreshold,
    newRuleCurrency,
    newRuleCondition,
    newRuleMinSample,
    newRuleWindowDays,
    newRuleBaseline,
    load,
  ]);

  const removeRule = useCallback(
    async (rule: CrmCustomerTagRuleRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteCustomerTagRule(rule.id);
        setNotice(`规则 ${rule.ruleCode} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '删规则失败（已有标签记录或运行记录时删不掉）'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const runRule = useCallback(
    async (rule: CrmCustomerTagRuleRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const run: CrmCustomerTagRunRow = await runCustomerTagRule(rule.id, {
          windowFrom: null,
          windowTo: null,
        });
        if (run.status === 'FAILED') {
          // 🔴 上游读不到 ⇒ 一条标签都没打。必须显式说清，
          //   否则「跑过了但没人命中」会被读成「这批人都不符合条件」。
          setActionError(
            `规则 ${rule.ruleCode} 这次没跑成：${run.failureReason}。本次**一条标签都没有打**。`,
          );
        } else {
          setNotice(
            `规则 ${rule.ruleCode} 跑完：命中 ${run.taggedCount}、幂等 ${run.unchangedCount}、` +
              `不可判定 ${run.indeterminateCount}、撤销 ${run.revokedCount}` +
              (run.truncated ? '（🔴 上游扫描被截断，名单不是全量）' : '') +
              '。',
          );
        }
        await load();
      } catch (e) {
        setActionError(errorText(e, '跑规则失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitManualTag = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createCustomerTagAssignment({
        customerRef: manualCustomerRef.trim(),
        customerName: manualCustomerName.trim(),
        tagId: manualTagId,
        reason: manualReason.trim(),
      });
      setNotice(`已给客户 ${manualCustomerRef.trim()} 打上标签。`);
      setManualCustomerRef('');
      setManualCustomerName('');
      setManualReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '手工打标失败（可争议标签必须写明原因）'));
    } finally {
      setBusy(false);
    }
  }, [manualCustomerRef, manualCustomerName, manualTagId, manualReason, load]);

  const reviewAssignment = useCallback(
    async (assignment: CrmCustomerTagAssignmentRow, decision: 'CONFIRM' | 'REVOKE') => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await reviewCustomerTagAssignment(assignment.id, {
          decision,
          reason: reviewReason.trim(),
        });
        setNotice(
          decision === 'CONFIRM'
            ? `已确认 ${assignment.tagName}（${assignment.customerRef}）。`
            : `已撤销 ${assignment.tagName}（${assignment.customerRef}）。自动规则重跑**不会**把它恢复。`,
        );
        setReviewReason('');
        await load();
      } catch (e) {
        setActionError(errorText(e, '复核失败（撤销必须写明原因）'));
      } finally {
        setBusy(false);
      }
    },
    [reviewReason, load],
  );

  const tagRows = tags?.items ?? [];
  const hasTagFilter = Boolean(tagCategory || tagStatus || tagKeyword);
  const hasAssignmentFilter = Boolean(
    assignmentTagId || assignmentSource || assignmentState || assignmentKeyword,
  );

  const availableDimensions = useMemo(
    () => (capabilities?.dimensions ?? []).filter((dimension) => dimension.available),
    [capabilities],
  );
  const unavailableDimensions = useMemo(
    () => (capabilities?.dimensions ?? []).filter((dimension) => !dimension.available),
    [capabilities],
  );

  if (loading && !tags) {
    return (
      <SectionCard title="客户标签">
        <p className="text-muted-foreground text-copy-13">正在读取客户标签……</p>
      </SectionCard>
    );
  }

  if (error && !tags) {
    return (
      <SectionCard title="客户标签">
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
                （归属模块 {dimension.ownerModule}）—— {dimension.impact}
              </span>
            </li>
          ))}
        </ul>
        <div className="border-warning/40 bg-warning/10 text-copy-13 mt-3 rounded-md px-3 py-2">
          <p className="flex items-start gap-2">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{capabilities?.actionPolicy}</span>
          </p>
          <p className="mt-2">{capabilities?.metricNote}</p>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">{capabilities?.boundaryNote}</p>
      </SectionCard>

      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      <Segmented
        value={view}
        onChange={setView}
        aria-label="客户标签工作区视图"
        options={[
          { value: 'tags', label: '标签定义', icon: <Tag className="h-4 w-4" /> },
          { value: 'rules', label: '打标规则', icon: <Play className="h-4 w-4" /> },
          { value: 'assignments', label: '标签记录', icon: <Tags className="h-4 w-4" /> },
          { value: 'distribution', label: '分布统计', icon: <UserCheck className="h-4 w-4" /> },
        ]}
      />

      {/* ---------------------------------------------------------- 标签定义 */}
      {view === 'tags' && (
        <>
          <SectionCard title="标签定义">
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="分类">
                <select
                  className={control}
                  value={tagCategory}
                  onChange={(e) => {
                    setTagCategory(e.target.value);
                    setTagPageIndex(0);
                  }}
                >
                  <option value="">全部分类</option>
                  {['VALUE', 'BEHAVIOR', 'RISK', 'SOURCE', 'CUSTOM'].map((c) => (
                    <option key={c} value={c}>
                      {customerTagCategoryLabel(c)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={tagStatus}
                  onChange={(e) => {
                    setTagStatus(e.target.value);
                    setTagPageIndex(0);
                  }}
                >
                  <option value="">全部状态</option>
                  <option value="ACTIVE">启用</option>
                  <option value="DISABLED">停用</option>
                </select>
              </Field>
              <Field label="关键字">
                <input
                  className={control}
                  value={tagKeyword}
                  onChange={(e) => {
                    setTagKeyword(e.target.value);
                    setTagPageIndex(0);
                  }}
                  placeholder="标签编码或名称"
                />
              </Field>
            </div>

            <div className="mt-4">
              {tagRows.length === 0 ? (
                hasTagFilter ? (
                  <EmptyState
                    icon={<Tag className="h-6 w-6" />}
                    title="当前筛选没有匹配的标签"
                    description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有标签定义」是两件不同的事。"
                  />
                ) : (
                  <EmptyState
                    icon={<Tag className="h-6 w-6" />}
                    title="还没有标签定义"
                    description="先在下方建一枚标签（VIP、复购、退货率高、高客单、风险、渠道来源…），再谈得上「谁身上有这枚标签」。"
                  />
                )
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">标签</th>
                      <th className="py-2">分类</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">复核要求</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {tagRows.map((tag) => (
                      <tr key={tag.id} className="border-border/60 border-t">
                        <td className="py-2">
                          <div className="font-medium">{tag.name}</div>
                          <div className="text-muted-foreground text-xs">{tag.tagCode}</div>
                          {tag.description && (
                            <div className="text-muted-foreground text-xs">{tag.description}</div>
                          )}
                        </td>
                        <td className="py-2">{tag.categoryLabel}</td>
                        <td className="py-2">
                          <Badge tone={tag.status === 'ACTIVE' ? 'success' : 'warning'}>
                            {tag.status === 'ACTIVE' ? '启用' : '停用'}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {tag.contestable ? (
                            <Badge tone="warning">可争议 · 必须写原因</Badge>
                          ) : tag.reviewRequired ? (
                            <Badge tone="warning">需人工复核</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="py-2 text-right">
                          {writable && (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void removeTag(tag.id, tag.tagCode)}
                              disabled={busy}
                            >
                              <Trash2 className="mr-1 h-4 w-4" />
                              删除
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {tags && tags.totalPages > 1 && (
                <div className="text-muted-foreground mt-3 flex items-center gap-2 text-xs">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={tagPageIndex <= 0}
                    onClick={() => setTagPageIndex((i) => Math.max(0, i - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    第 {tags.page + 1} / {tags.totalPages} 页，共 {tags.totalElements} 枚
                  </span>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={tagPageIndex + 1 >= tags.totalPages}
                    onClick={() => setTagPageIndex((i) => i + 1)}
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>
          </SectionCard>

          {writable && (
            <SectionCard title="新建标签定义">
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="标签编码" hint="租户内唯一，建后不可改。">
                  <input
                    className={control}
                    value={newTagCode}
                    onChange={(e) => setNewTagCode(e.target.value)}
                    placeholder="VIP / REPEAT / HIGH_RETURN"
                  />
                </Field>
                <Field label="标签名称">
                  <input
                    className={control}
                    value={newTagName}
                    onChange={(e) => setNewTagName(e.target.value)}
                    placeholder="VIP 客户"
                  />
                </Field>
                <Field label="分类">
                  <select
                    className={control}
                    value={newTagCategory}
                    onChange={(e) => setNewTagCategory(e.target.value)}
                  >
                    {['VALUE', 'BEHAVIOR', 'RISK', 'SOURCE', 'CUSTOM'].map((c) => (
                      <option key={c} value={c}>
                        {customerTagCategoryLabel(c)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="说明">
                  <input
                    className={control}
                    value={newTagDescription}
                    onChange={(e) => setNewTagDescription(e.target.value)}
                    placeholder="这枚标签代表什么"
                  />
                </Field>
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-4">
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={newTagContestable}
                    onChange={(e) => setNewTagContestable(e.target.checked)}
                  />
                  可争议标签（退货率高 / 风险）—— 打上时**必须**写原因
                </label>
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={newTagReviewRequired}
                    onChange={(e) => setNewTagReviewRequired(e.target.checked)}
                  />
                  打上之后必须人工复核
                </label>
              </div>
              <div className="mt-3">
                <Button
                  onClick={() => void submitTag()}
                  disabled={busy || !newTagCode || !newTagName}
                >
                  创建标签
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 打标规则 */}
      {view === 'rules' && (
        <>
          <SectionCard title="自动打标规则">
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="判定口径">
                <select
                  className={control}
                  value={ruleMetric}
                  onChange={(e) => {
                    setRuleMetric(e.target.value);
                    setRulePageIndex(0);
                  }}
                >
                  <option value="">全部口径</option>
                  {[
                    'ORDER_COUNT',
                    'REPEAT_PURCHASE',
                    'HIGH_VALUE',
                    'RETURN_RATE',
                    'CHANNEL_SOURCE',
                  ].map((m) => (
                    <option key={m} value={m}>
                      {customerTagMetricLabel(m)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={ruleStatus}
                  onChange={(e) => {
                    setRuleStatus(e.target.value);
                    setRulePageIndex(0);
                  }}
                >
                  <option value="">全部状态</option>
                  <option value="ACTIVE">启用</option>
                  <option value="DISABLED">停用</option>
                </select>
              </Field>
            </div>

            <div className="mt-4">
              {(rules?.items ?? []).length === 0 ? (
                <EmptyState
                  icon={<Play className="h-6 w-6" />}
                  title="还没有打标规则"
                  description="规则决定「按订单行为自动打标」的口径。样本量与基线倍率都在规则上配 —— 这两道闸是「退货率高」这类标签不敢乱用的原因。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">规则</th>
                      <th className="py-2">口径</th>
                      <th className="py-2">门槛</th>
                      <th className="py-2">最近一次</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {(rules?.items ?? []).map((rule) => (
                      <tr key={rule.id} className="border-border/60 border-t">
                        <td className="py-2">
                          <div className="font-medium">{rule.name}</div>
                          <div className="text-muted-foreground text-xs">
                            {rule.ruleCode} → {rule.tagName}
                          </div>
                        </td>
                        <td className="py-2">
                          <div>{rule.metricLabel}</div>
                          {rule.metric === 'CHANNEL_SOURCE' ? (
                            <div className="text-muted-foreground text-xs">
                              渠道来源 = {rule.conditionValue}
                            </div>
                          ) : (
                            <div className="text-muted-foreground text-xs">
                              阈值 {rule.threshold}
                              {rule.currency ? ` ${rule.currency}` : ''}
                            </div>
                          )}
                        </td>
                        <td className="py-2">
                          <div className="text-xs">最低样本 {rule.minSample} 单</div>
                          <div className="text-muted-foreground text-xs">
                            窗口 {rule.windowDays} 天
                          </div>
                          {rule.metric === 'RETURN_RATE' && (
                            <div className="text-muted-foreground text-xs">
                              基线倍率 {rule.baselineMultiplier} 倍
                            </div>
                          )}
                        </td>
                        <td className="py-2">
                          {rule.lastRunNo ? (
                            <>
                              <div>{rule.lastRunNo}</div>
                              <div className="text-muted-foreground text-xs">
                                {rule.lastRunStatus === 'FAILED'
                                  ? '失败（未打任何标签）'
                                  : '已完成'}
                              </div>
                            </>
                          ) : (
                            <span className="text-muted-foreground">还没跑过</span>
                          )}
                        </td>
                        <td className="py-2 text-right">
                          {writable && (
                            <div className="flex justify-end gap-1">
                              <Button
                                size="sm"
                                variant="secondary"
                                onClick={() => void runRule(rule)}
                                disabled={busy}
                              >
                                <Play className="mr-1 h-4 w-4" />
                                跑一次
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => void removeRule(rule)}
                                disabled={busy}
                              >
                                <Trash2 className="mr-1 h-4 w-4" />
                                删除
                              </Button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </SectionCard>

          <SectionCard title="运行记录与逐客户判定">
            {(runs?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<Play className="h-6 w-6" />}
                title="还没有跑过规则"
                description="跑一次之后这里会显示：命中多少、幂等多少、**不可判定**多少、租户基线是多少、上游扫描有没有被截断。"
              />
            ) : (
              <div className="space-y-4">
                {(runs?.items ?? []).map((run) => (
                  <RunDetail key={run.id} run={run} />
                ))}
              </div>
            )}
          </SectionCard>

          {writable && (
            <SectionCard title="新建打标规则">
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="规则编码" hint="租户内唯一，建后不可改。">
                  <input
                    className={control}
                    value={newRuleCode}
                    onChange={(e) => setNewRuleCode(e.target.value)}
                    placeholder="REPEAT-3"
                  />
                </Field>
                <Field label="规则名称">
                  <input
                    className={control}
                    value={newRuleName}
                    onChange={(e) => setNewRuleName(e.target.value)}
                    placeholder="窗口内 3 单及以上算复购"
                  />
                </Field>
                <Field label="命中后打哪枚标签">
                  <select
                    className={control}
                    value={newRuleTagId}
                    onChange={(e) => setNewRuleTagId(e.target.value)}
                  >
                    <option value="">请选择标签</option>
                    {tagRows.map((tag) => (
                      <option key={tag.id} value={tag.id}>
                        {tag.name}（{tag.tagCode}）
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="判定口径">
                  <select
                    className={control}
                    value={newRuleMetric}
                    onChange={(e) => setNewRuleMetric(e.target.value)}
                  >
                    {[
                      'ORDER_COUNT',
                      'REPEAT_PURCHASE',
                      'HIGH_VALUE',
                      'RETURN_RATE',
                      'CHANNEL_SOURCE',
                    ].map((m) => (
                      <option key={m} value={m}>
                        {customerTagMetricLabel(m)}
                      </option>
                    ))}
                  </select>
                </Field>
                {newRuleMetric === 'CHANNEL_SOURCE' ? (
                  <Field label="渠道来源" hint="与订单事实里的 orderSource 比对（不区分大小写）。">
                    <input
                      className={control}
                      value={newRuleCondition}
                      onChange={(e) => setNewRuleCondition(e.target.value)}
                      placeholder="AMAZON"
                    />
                  </Field>
                ) : (
                  <Field label="阈值">
                    <input
                      className={control}
                      value={newRuleThreshold}
                      onChange={(e) => setNewRuleThreshold(e.target.value)}
                      placeholder="3"
                    />
                  </Field>
                )}
                {newRuleMetric === 'HIGH_VALUE' && (
                  <Field
                    label="币种"
                    hint="金额按币种分开判定，USD 与 EUR 加起来不是任何一个金额。"
                  >
                    <input
                      className={control}
                      value={newRuleCurrency}
                      onChange={(e) => setNewRuleCurrency(e.target.value)}
                      placeholder="USD"
                    />
                  </Field>
                )}
                <Field label="最低样本量" hint="低于它 ⇒ 结论是「不可判定」，不是「是」。">
                  <input
                    className={control}
                    value={newRuleMinSample}
                    onChange={(e) => setNewRuleMinSample(e.target.value)}
                  />
                </Field>
                <Field label="统计窗口（天）">
                  <input
                    className={control}
                    value={newRuleWindowDays}
                    onChange={(e) => setNewRuleWindowDays(e.target.value)}
                  />
                </Field>
                {newRuleMetric === 'RETURN_RATE' && (
                  <Field
                    label="基线倍率"
                    hint="必须大于 1：等于 1 是「不低于基线」，那不叫「显著高于」。"
                  >
                    <input
                      className={control}
                      value={newRuleBaseline}
                      onChange={(e) => setNewRuleBaseline(e.target.value)}
                    />
                  </Field>
                )}
              </div>
              <div className="mt-3">
                <Button
                  onClick={() => void submitRule()}
                  disabled={busy || !newRuleCode || !newRuleName || !newRuleTagId}
                >
                  创建规则
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 标签记录 */}
      {view === 'assignments' && (
        <>
          <SectionCard title="标签记录">
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="标签">
                <select
                  className={control}
                  value={assignmentTagId}
                  onChange={(e) => {
                    setAssignmentTagId(e.target.value);
                    setAssignmentPageIndex(0);
                  }}
                >
                  <option value="">全部标签</option>
                  {tagRows.map((tag) => (
                    <option key={tag.id} value={tag.id}>
                      {tag.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="来源">
                <select
                  className={control}
                  value={assignmentSource}
                  onChange={(e) => {
                    setAssignmentSource(e.target.value);
                    setAssignmentPageIndex(0);
                  }}
                >
                  <option value="">全部来源</option>
                  <option value="MANUAL">人工打标</option>
                  <option value="RULE">规则打标</option>
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={assignmentState}
                  onChange={(e) => {
                    setAssignmentState(e.target.value);
                    setAssignmentPageIndex(0);
                  }}
                >
                  <option value="">全部状态</option>
                  <option value="ACTIVE">生效</option>
                  <option value="REVOKED">已撤销</option>
                </select>
              </Field>
              <Field label="客户关键字">
                <input
                  className={control}
                  value={assignmentKeyword}
                  onChange={(e) => {
                    setAssignmentKeyword(e.target.value);
                    setAssignmentPageIndex(0);
                  }}
                  placeholder="客户标识或名称"
                />
              </Field>
            </div>

            <div className="mt-4">
              {(assignments?.items ?? []).length === 0 ? (
                hasAssignmentFilter ? (
                  <EmptyState
                    icon={<Tags className="h-6 w-6" />}
                    title="当前筛选没有匹配的标签记录"
                    description="把筛选条件放宽一点再试。"
                  />
                ) : (
                  <EmptyState
                    icon={<Tags className="h-6 w-6" />}
                    title="还没有任何标签记录"
                    description="先跑一次规则，或在下方手工给某位客户打一枚标签。"
                  />
                )
              ) : (
                <div className="space-y-2">
                  {(assignments?.items ?? []).map((assignment) => (
                    <AssignmentRow
                      key={assignment.id}
                      assignment={assignment}
                      writable={writable}
                      busy={busy}
                      reviewReason={reviewReason}
                      onReviewReason={setReviewReason}
                      onReview={reviewAssignment}
                    />
                  ))}
                </div>
              )}
              {assignments && assignments.totalPages > 1 && (
                <div className="text-muted-foreground mt-3 flex items-center gap-2 text-xs">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={assignmentPageIndex <= 0}
                    onClick={() => setAssignmentPageIndex((i) => Math.max(0, i - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    第 {assignments.page + 1} / {assignments.totalPages} 页，共{' '}
                    {assignments.totalElements} 条
                  </span>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={assignmentPageIndex + 1 >= assignments.totalPages}
                    onClick={() => setAssignmentPageIndex((i) => i + 1)}
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>
          </SectionCard>

          {writable && (
            <SectionCard title="手工打标">
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="客户标识" hint="来自上游订单事实的 customer_party_id。">
                  <input
                    className={control}
                    value={manualCustomerRef}
                    onChange={(e) => setManualCustomerRef(e.target.value)}
                  />
                </Field>
                <Field label="客户名称">
                  <input
                    className={control}
                    value={manualCustomerName}
                    onChange={(e) => setManualCustomerName(e.target.value)}
                  />
                </Field>
                <Field label="标签">
                  <select
                    className={control}
                    value={manualTagId}
                    onChange={(e) => setManualTagId(e.target.value)}
                  >
                    <option value="">请选择标签</option>
                    {tagRows.map((tag) => (
                      <option key={tag.id} value={tag.id}>
                        {tag.name}
                        {tag.contestable ? '（可争议）' : ''}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label="原因"
                  hint="可争议标签（退货率高 / 风险）**必填** —— 没有原因就没法复核，也没法纠正。"
                >
                  <input
                    className={control}
                    value={manualReason}
                    onChange={(e) => setManualReason(e.target.value)}
                    placeholder="依据是什么"
                  />
                </Field>
              </div>
              <div className="mt-3">
                <Button
                  onClick={() => void submitManualTag()}
                  disabled={busy || !manualCustomerRef || !manualTagId}
                >
                  打标
                </Button>
              </div>

              <div className="border-border/60 mt-5 border-t pt-4">
                <p className="text-muted-foreground text-copy-13">
                  候选客户来自**上游订单事实的真实聚合**（不是本模块臆造的名单）。
                  客户档案（15.3.1.1）未交付，所以这里只能按上游事实列客户。
                </p>
                <div className="mt-3 flex flex-wrap items-end gap-2">
                  <div className="min-w-56 flex-1">
                    <Field label="按客户标识或名称查">
                      <input
                        className={control}
                        value={candidateKeyword}
                        onChange={(e) => setCandidateKeyword(e.target.value)}
                      />
                    </Field>
                  </div>
                  <Button onClick={() => void loadCandidates()} variant="secondary" disabled={busy}>
                    查候选客户
                  </Button>
                </div>
                {candidates && (
                  <div className="mt-3 space-y-2">
                    {candidates.truncated && (
                      <div className="border-warning/40 bg-warning/10 text-copy-13 rounded-md px-3 py-2">
                        🔴 本次扫描**被截断**（扫到上限了），名单不是全量。
                      </div>
                    )}
                    <p className="text-muted-foreground text-xs">{candidates.note}</p>
                    {candidates.items.length === 0 ? (
                      <EmptyState
                        icon={<UserCheck className="h-6 w-6" />}
                        title="没有匹配的候选客户"
                        description="上游订单事实里没有匹配的客户 —— 换个关键字，或确认这个租户真的有订单。"
                      />
                    ) : (
                      candidates.items.map((candidate) => (
                        <div
                          key={candidate.customerRef}
                          className="border-border/60 flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                        >
                          <div>
                            <div className="font-medium">
                              {candidate.customerName || '（未提供名称）'}
                            </div>
                            <div className="text-muted-foreground text-xs">
                              {candidate.customerRef} · {candidate.orderCount} 单 ·{' '}
                              {Object.entries(candidate.amountsByCurrency)
                                .map(([currency, amount]) => `${currency} ${amount}`)
                                .join(' / ') || '无金额'}
                            </div>
                          </div>
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => {
                              setManualCustomerRef(candidate.customerRef);
                              setManualCustomerName(candidate.customerName);
                            }}
                          >
                            选这位客户
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                )}
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* ---------------------------------------------------------- 分布统计 */}
      {view === 'distribution' && (
        <SectionCard title="标签分布统计">
          <div className="grid gap-3 md:grid-cols-2">
            <StatCard
              label="被打过标签的客户"
              value={distribution?.totalTaggedCustomers ?? 0}
              comparisonLabel="分母：持有任意生效标签的去重客户数"
            />
            <StatCard
              label="生效中的标签记录"
              value={distribution?.totalActiveAssignments ?? 0}
              comparisonLabel="客户 × 标签，人工与自动都算"
            />
          </div>
          <p className="text-muted-foreground mt-3 text-xs">{distribution?.note}</p>
          <div className="mt-4">
            {(distribution?.rows ?? []).length === 0 ? (
              <EmptyState
                icon={<Tags className="h-6 w-6" />}
                title="还没有可统计的标签"
                description="建一枚标签并打上标之后，这里会显示每枚标签的客户数与占比。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">标签</th>
                    <th className="py-2">分类</th>
                    <th className="py-2">生效客户</th>
                    <th className="py-2">人工 / 规则</th>
                    <th className="py-2">待复核</th>
                    <th className="py-2">已撤销</th>
                    <th className="py-2">占比</th>
                  </tr>
                </thead>
                <tbody>
                  {(distribution?.rows ?? []).map((row) => (
                    <tr key={row.tagId} className="border-border/60 border-t">
                      <td className="py-2">
                        <div className="font-medium">{row.tagName}</div>
                        <div className="text-muted-foreground text-xs">{row.tagCode}</div>
                      </td>
                      <td className="py-2">{row.categoryLabel}</td>
                      <td className="py-2">{row.activeCustomerCount}</td>
                      <td className="py-2">
                        {row.manualCustomerCount} / {row.ruleCustomerCount}
                      </td>
                      <td className="py-2">
                        {row.pendingReviewCount > 0 ? (
                          <Badge tone="warning">{row.pendingReviewCount}</Badge>
                        ) : (
                          <span className="text-muted-foreground">0</span>
                        )}
                      </td>
                      <td className="py-2">{row.revokedCount}</td>
                      <td className="py-2">{customerTagShareText(row.sharePercent)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </SectionCard>
      )}
    </div>
  );
}

/** 一条标签记录：来源分开显示，判定依据可展开。 */
function AssignmentRow({
  assignment,
  writable,
  busy,
  reviewReason,
  onReviewReason,
  onReview,
}: {
  assignment: CrmCustomerTagAssignmentRow;
  writable: boolean;
  busy: boolean;
  reviewReason: string;
  onReviewReason: (value: string) => void;
  onReview: (
    assignment: CrmCustomerTagAssignmentRow,
    decision: 'CONFIRM' | 'REVOKE',
  ) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const evidence = customerTagEvidence(assignment.evidence);
  const summary = customerTagEvidenceSummary(evidence);

  return (
    <div className="border-border/60 rounded-md border px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{assignment.tagName}</span>
            <Badge tone={assignment.source === 'MANUAL' ? 'info' : 'success'}>
              {customerTagSourceLabel(assignment.source)}
            </Badge>
            <Badge tone={assignment.state === 'ACTIVE' ? 'success' : 'warning'}>
              {customerTagStateLabel(assignment.state)}
            </Badge>
            {assignment.contestable && <Badge tone="warning">可争议</Badge>}
            {assignment.pendingReview && <Badge tone="warning">待人工复核</Badge>}
          </div>
          <div className="text-muted-foreground text-xs">
            {assignment.customerName || '（未提供名称）'} · {assignment.customerRef}
            {assignment.ruleCode ? ` · 规则 ${assignment.ruleCode}` : ''}
          </div>
          {assignment.reason && <div className="text-copy-13 mt-1">原因：{assignment.reason}</div>}
        </div>
        <div className="flex items-center gap-1">
          {summary.length > 0 && (
            <Button size="sm" variant="ghost" onClick={() => setOpen((v) => !v)}>
              {open ? '收起依据' : '看判定依据'}
            </Button>
          )}
          {writable && assignment.pendingReview && (
            <Button
              size="sm"
              variant="secondary"
              disabled={busy}
              onClick={() => void onReview(assignment, 'CONFIRM')}
            >
              确认
            </Button>
          )}
          {writable && assignment.state === 'ACTIVE' && (
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={() => void onReview(assignment, 'REVOKE')}
            >
              撤销
            </Button>
          )}
        </div>
      </div>
      {open && (
        <div className="border-border/60 mt-2 border-t pt-2">
          <ul className="text-muted-foreground space-y-1 text-xs">
            {summary.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      )}
      {writable && assignment.contestable && (
        <div className="mt-2 flex items-end gap-2">
          <div className="min-w-56 flex-1">
            <Field label="复核原因" hint="撤销时必填 —— 没有原因的纠正只是另一次没有依据的判断。">
              <input
                className={control}
                value={reviewReason}
                onChange={(e) => onReviewReason(e.target.value)}
              />
            </Field>
          </div>
        </div>
      )}
    </div>
  );
}

/** 一次运行 + 它的逐客户判定结论。 */
function RunDetail({ run }: { run: CrmCustomerTagRunRow }) {
  const [evaluations, setEvaluations] = useState<Awaited<
    ReturnType<typeof fetchCustomerTagEvaluations>
  > | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setEvaluations(await fetchCustomerTagEvaluations(run.id, { size: 50 }));
    } catch (e) {
      setError(errorText(e, '判定结论读不到'));
    } finally {
      setLoading(false);
    }
  }, [run.id]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="border-border/60 rounded-md border px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="font-medium">
            {run.runNo} · {run.ruleCode}
          </div>
          <div className="text-muted-foreground text-xs">
            窗口 {run.windowFrom} ~ {run.windowTo}
          </div>
        </div>
        <Badge tone={run.status === 'SUCCEEDED' ? 'success' : 'warning'}>
          {run.status === 'SUCCEEDED' ? '已完成' : '失败（未打任何标签）'}
        </Badge>
      </div>
      {run.status === 'FAILED' ? (
        <div className="border-destructive/40 bg-destructive/10 text-destructive mt-2 rounded-md px-3 py-2 text-xs">
          {run.failureReason} —— 本次**一条标签都没有打**。
        </div>
      ) : (
        <div className="text-muted-foreground mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
          <span>客户 {run.customerCount}</span>
          <span>命中 {run.taggedCount}</span>
          <span>幂等 {run.unchangedCount}</span>
          <span>不可判定 {run.indeterminateCount}</span>
          <span>撤销 {run.revokedCount}</span>
          <span>人工优先未恢复 {run.revokedSkippedCount}</span>
          {run.baseline !== null && <span>租户基线 {run.baseline}</span>}
          {run.truncated && <span className="text-warning">🔴 上游扫描被截断，名单不是全量</span>}
        </div>
      )}
      <div className="mt-2">
        {loading ? (
          <p className="text-muted-foreground text-xs">正在读取逐客户判定……</p>
        ) : error ? (
          <p className="text-destructive text-xs">{error}</p>
        ) : (evaluations?.items ?? []).length === 0 ? (
          <p className="text-muted-foreground text-xs">这次运行没有留下逐客户判定结论。</p>
        ) : (
          <table className="w-full text-left text-xs">
            <thead className="text-muted-foreground">
              <tr>
                <th className="py-1">客户</th>
                <th className="py-1">结论</th>
                <th className="py-1">理由</th>
              </tr>
            </thead>
            <tbody>
              {(evaluations?.items ?? []).map((evaluation) => (
                <tr key={evaluation.id} className="border-border/60 border-t">
                  <td className="py-1">
                    {evaluation.customerName || '（未提供名称）'}
                    <div className="text-muted-foreground">{evaluation.customerRef}</div>
                  </td>
                  <td className="py-1">
                    <Badge
                      tone={
                        evaluation.outcome === 'INDETERMINATE'
                          ? 'warning'
                          : evaluation.outcome === 'TAGGED' ||
                              evaluation.outcome === 'ALREADY_TAGGED'
                            ? 'success'
                            : 'neutral'
                      }
                    >
                      {evaluation.outcomeLabel}
                    </Badge>
                  </td>
                  <td className="py-1">{evaluation.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
