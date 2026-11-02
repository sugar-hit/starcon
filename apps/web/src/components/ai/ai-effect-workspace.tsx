'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import { CircleAlert, Gauge, Loader2, RefreshCw, Target, TriangleAlert } from 'lucide-react';
import {
  adoptionDecisionLabel,
  adoptionDecisionTone,
  adoptionRateFacts,
  artifactTypeLabel,
  backtestStatusTone,
  decideSuggestion,
  factStatusLabel,
  factStatusTone,
  FEEDBACK_REASON_LABELS,
  fetchAdoptions,
  fetchAdoptionReport,
  fetchBacktests,
  fetchCapabilities,
  fetchFeedback,
  fetchOverview,
  fetchSuggestions,
  fetchThresholds,
  formatCount,
  formatInstant,
  formatRate,
  generateSuggestions,
  memberLabel,
  normalizeThresholdInput,
  recordAdoption,
  recordFeedback,
  runBacktest,
  saveThreshold,
  suggestionFacts,
  suggestionSeverityTone,
  suggestionStatusLabel,
  suggestionStatusTone,
  suggestionTypeLabel,
  suggestionTypeTone,
  thresholdFacts,
  toAdoptionInput,
  toFeedbackInput,
  trimDecimal,
  usefulnessLabel,
  usefulnessTone,
  validateAdoptionInput,
  validateFeedbackInput,
  validateThresholdInput,
  verdictLabel,
  verdictTone,
  withdrawAdoption,
  withdrawFeedback,
  type AdoptionForm,
  type FeedbackForm,
  type Tone,
} from './ai-effect-model';
import type {
  AiAdoptionPage,
  AiAdoptionReportPage,
  AiBacktestPage,
  AiEffectCapabilities,
  AiEffectOverview,
  AiEffectThresholdRow,
  AiFeedbackPage,
  AiSuggestionPage,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.3.2「效果评估」工作区（卷 18 前端入口 `/ai/effects`）。
 *
 * 蓝图：「**职责**：评估 AI 功能效果：翻译采纳率、预测准确度回测、助手答案有用性反馈，
 * 识别低价值功能并下线。**输入**：人工采纳/修改记录；预测回测结果；用户反馈。
 * **输出**：效果报表 + 改进建议。**关系**：上游 = 18.4 全部；下游 = 18.4 迭代。
 * **采纳率是 AI 功能唯一诚实的指标**——生成了但没人用的功能应下线。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **分母只有一个来源**：18.4.3.1 的用量台账（AI 调用次数）。本模块只贡献分子。
 *    分母为 0 ⇒ 比率是 `null`，页面显示「—」——**不画成 0%**。
 * 2. **只出建议，不下线**：本仓没有按能力粒度的 AI 功能开关 ⇒ 没有下线端点。
 *    决策端点只记录「采纳 / 驳回」，页面**不写「已下线」**。
 * 3. **助手答案这一维如实答不可用**：上游 18.4.1.1 已熔断未交付 ⇒ 能力声明里
 *    `available=false` 的项按原样显示，页面**不伪造**助手的使用 / 反馈数据。
 * 4. **事实不可改写，但可受控撤回**（ACTIVE → WITHDRAWN）：填错了要能纠正，
 *    否则一条错记录会让采纳率永远偏。
 * 5. **主体成员不是前端能填的**：请求体里没有 membershipId，归属由服务端从会话解析。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有数据」。
 */

type Tab =
  | 'overview'
  | 'adoptions'
  | 'report'
  | 'feedback'
  | 'backtests'
  | 'suggestions'
  | 'thresholds'
  | 'capabilities';

const PAGE_SIZE = 10;

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const DECISIONS = [
  { value: 'ACCEPTED', label: '直接采用' },
  { value: 'MODIFIED', label: '修改后采用' },
  { value: 'REJECTED', label: '拒绝' },
];

const USEFULNESS = [
  { value: 'USEFUL', label: '有用' },
  { value: 'PARTIALLY_USEFUL', label: '部分有用' },
  { value: 'NOT_USEFUL', label: '无用' },
];

const ARTIFACT_TYPES = [
  { value: 'TRANSLATION', label: '译文' },
  { value: 'FORECAST', label: '预测结果' },
  { value: 'ASSISTANT', label: '助手答案' },
  { value: 'OTHER', label: '其它 AI 产物' },
];

const BACKTEST_STATUS = [
  { value: 'COMPARED', label: '已回测' },
  { value: 'NO_ACTUAL_SOURCE', label: '无法回测，缺实际值来源' },
  { value: 'NOT_COMPARABLE', label: '不可比' },
];

const SUGGESTION_TYPES = [
  { value: 'OFFLINE_CANDIDATE', label: '低价值功能，建议下线' },
  { value: 'LOW_USEFULNESS', label: '有用性偏低' },
  { value: 'FORECAST_INACCURACY', label: '预测准确度偏低' },
  { value: 'DATA_GAP', label: '数据不足' },
];

const SUGGESTION_STATUS = [
  { value: 'OPEN', label: '待决策' },
  { value: 'ACCEPTED', label: '已采纳' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'RESOLVED', label: '条件已不成立，已自动关闭' },
];

const EMPTY_ADOPTION: AdoptionForm = {
  capabilityCode: '',
  featureCode: '',
  decision: 'ACCEPTED',
  artifactType: 'TRANSLATION',
  artifactId: '',
  artifactVersion: '',
  artifactRef: '',
  usageRecordId: '',
  editNote: '',
  rejectReason: '',
};

const EMPTY_FEEDBACK: FeedbackForm = {
  capabilityCode: '',
  featureCode: '',
  usefulness: 'USEFUL',
  reasonCode: '',
  reasonText: '',
  artifactType: 'TRANSLATION',
  artifactId: '',
  artifactVersion: '',
  artifactRef: '',
};

interface ThresholdForm {
  minCalls: string;
  lowAdoptionRate: string;
  minFeedbackCount: string;
  lowUsefulRate: string;
  maxMape: string;
}

const EMPTY_THRESHOLD: ThresholdForm = {
  minCalls: '',
  lowAdoptionRate: '',
  minFeedbackCount: '',
  lowUsefulRate: '',
  maxMape: '',
};

export function AiEffectWorkspace() {
  const [tab, setTab] = useState<Tab>('overview');

  const [capabilities, setCapabilities] = useState<AiEffectCapabilities | null>(null);
  const [overview, setOverview] = useState<AiEffectOverview | null>(null);
  const [thresholds, setThresholds] = useState<AiEffectThresholdRow | null>(null);
  const [adoptions, setAdoptions] = useState<AiAdoptionPage | null>(null);
  const [report, setReport] = useState<AiAdoptionReportPage | null>(null);
  const [feedback, setFeedback] = useState<AiFeedbackPage | null>(null);
  const [backtests, setBacktests] = useState<AiBacktestPage | null>(null);
  const [suggestions, setSuggestions] = useState<AiSuggestionPage | null>(null);

  const [capabilityFilter, setCapabilityFilter] = useState('');
  const [featureFilter, setFeatureFilter] = useState('');
  const [decisionFilter, setDecisionFilter] = useState('');
  const [usefulnessFilter, setUsefulnessFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [skuFilter, setSkuFilter] = useState('');
  const [suggestionTypeFilter, setSuggestionTypeFilter] = useState('');

  const [pageIndex, setPageIndex] = useState(0);

  const [adoptionForm, setAdoptionForm] = useState<AdoptionForm>(EMPTY_ADOPTION);
  const [feedbackForm, setFeedbackForm] = useState<FeedbackForm>(EMPTY_FEEDBACK);
  const [thresholdForm, setThresholdForm] = useState<ThresholdForm>(EMPTY_THRESHOLD);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请稍后重试');
  };

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, thr, adopt, rep, fb, bt, sug] = await Promise.all([
        fetchCapabilities(),
        fetchOverview({}),
        fetchThresholds(),
        fetchAdoptions(0, PAGE_SIZE, {}),
        fetchAdoptionReport(0, PAGE_SIZE, {}),
        fetchFeedback(0, PAGE_SIZE, {}),
        fetchBacktests(0, PAGE_SIZE, {}),
        fetchSuggestions(0, PAGE_SIZE, {}),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setThresholds(thr);
      setAdoptions(adopt);
      setReport(rep);
      setFeedback(fb);
      setBacktests(bt);
      setSuggestions(sug);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const reloadAdoptions = useCallback(async () => {
    try {
      setAdoptions(
        await fetchAdoptions(pageIndex, PAGE_SIZE, {
          capabilityCode: capabilityFilter || undefined,
          featureCode: featureFilter || undefined,
          decision: decisionFilter || undefined,
        }),
      );
    } catch (e) {
      fail(e);
    }
  }, [capabilityFilter, featureFilter, decisionFilter, pageIndex]);

  const reloadReport = useCallback(async () => {
    try {
      setReport(
        await fetchAdoptionReport(pageIndex, PAGE_SIZE, {
          capabilityCode: capabilityFilter || undefined,
        }),
      );
    } catch (e) {
      fail(e);
    }
  }, [capabilityFilter, pageIndex]);

  const reloadFeedback = useCallback(async () => {
    try {
      setFeedback(
        await fetchFeedback(pageIndex, PAGE_SIZE, {
          capabilityCode: capabilityFilter || undefined,
          featureCode: featureFilter || undefined,
          usefulness: usefulnessFilter || undefined,
        }),
      );
    } catch (e) {
      fail(e);
    }
  }, [capabilityFilter, featureFilter, usefulnessFilter, pageIndex]);

  const reloadBacktests = useCallback(async () => {
    try {
      setBacktests(
        await fetchBacktests(pageIndex, PAGE_SIZE, {
          skuCode: skuFilter || undefined,
          status: statusFilter || undefined,
        }),
      );
    } catch (e) {
      fail(e);
    }
  }, [skuFilter, statusFilter, pageIndex]);

  const reloadSuggestions = useCallback(async () => {
    try {
      setSuggestions(
        await fetchSuggestions(pageIndex, PAGE_SIZE, {
          status: statusFilter || undefined,
          suggestionType: suggestionTypeFilter || undefined,
          capabilityCode: capabilityFilter || undefined,
        }),
      );
    } catch (e) {
      fail(e);
    }
  }, [statusFilter, suggestionTypeFilter, capabilityFilter, pageIndex]);

  useEffect(() => {
    if (tab === 'adoptions') void reloadAdoptions();
  }, [tab, reloadAdoptions]);
  useEffect(() => {
    if (tab === 'report') void reloadReport();
  }, [tab, reloadReport]);
  useEffect(() => {
    if (tab === 'feedback') void reloadFeedback();
  }, [tab, reloadFeedback]);
  useEffect(() => {
    if (tab === 'backtests') void reloadBacktests();
  }, [tab, reloadBacktests]);
  useEffect(() => {
    if (tab === 'suggestions') void reloadSuggestions();
  }, [tab, reloadSuggestions]);

  // ---- 写入动作

  const submitAdoption = async () => {
    const problem = validateAdoptionInput(adoptionForm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    try {
      await recordAdoption(toAdoptionInput(adoptionForm));
      setAdoptionForm(EMPTY_ADOPTION);
      setNotice('已登记一条人工处置。');
      await reloadAdoptions();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const submitFeedback = async () => {
    const problem = validateFeedbackInput(feedbackForm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    try {
      await recordFeedback(toFeedbackInput(feedbackForm));
      setFeedbackForm(EMPTY_FEEDBACK);
      setNotice('已登记一条有用性反馈。');
      await reloadFeedback();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doWithdrawAdoption = async (id: string) => {
    setBusy(true);
    setError('');
    try {
      await withdrawAdoption(id, { reason: '页面撤回' });
      setNotice('已撤回该条采纳记录（撤回后不再计入采纳率的分子）。');
      await reloadAdoptions();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doWithdrawFeedback = async (id: string) => {
    setBusy(true);
    setError('');
    try {
      await withdrawFeedback(id, { reason: '页面撤回' });
      setNotice('已撤回该条有用性反馈（撤回后不再计入有用性聚合）。');
      await reloadFeedback();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doRunBacktest = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await runBacktest({ runId: null });
      setNotice(
        `回测完成：评估 ${formatCount(result.evaluatedCount)} 条，实际可比 ${formatCount(result.comparedCount)} 条，` +
          `MAPE ${formatRate(result.mape)}，命中区间率 ${formatRate(result.hitRate)}。`,
      );
      await reloadBacktests();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const submitThreshold = async () => {
    const problem = validateThresholdInput(thresholdForm);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const saved = await saveThreshold(normalizeThresholdInput(thresholdForm));
      setThresholds(saved);
      setThresholdForm(EMPTY_THRESHOLD);
      setNotice('已保存评估阈值。');
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doGenerateSuggestions = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await generateSuggestions({ from: null, to: null });
      setNotice(
        `建议已生成：新建 ${formatCount(result.created)} 条，刷新 ${formatCount(result.refreshed)} 条，` +
          `自动关闭 ${formatCount(result.resolved)} 条。`,
      );
      await reloadSuggestions();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doDecide = async (id: string, decision: 'ACCEPTED' | 'REJECTED') => {
    setBusy(true);
    setError('');
    try {
      await decideSuggestion(id, decision, { note: '页面决策' });
      setNotice(
        decision === 'ACCEPTED'
          ? '已记录「采纳建议」。⚠️ 本模块**不执行下线** —— 真正的下线动作需人工执行。'
          : '已记录「驳回建议」。',
      );
      await reloadSuggestions();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ---- 派生展示

  /** 上游未交付的能力（按后端的能力声明如实列出，不在前端另写一份名单）。 */
  const unavailable = useMemo(
    () => (capabilities?.capabilities ?? []).filter((cap) => !cap.available),
    [capabilities],
  );

  const adoptionSummary = overview?.adoption;
  const forecastSummary = overview?.forecast;
  const usefulnessSummary = overview?.usefulness;

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center gap-2 py-10">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载 AI 效果评估…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="AI 效果评估"
        description="卷 18 · 18.4.3.2 效果评估：翻译采纳率、预测准确度回测、助手答案有用性反馈，识别低价值功能并给出改进建议。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void loadAll()} disabled={busy}>
            <RefreshCw className="h-3.5 w-3.5" />
            刷新
          </Button>
        }
      />

      {/* 🔴 三条边界摆在最上面，不藏在折叠里 */}
      <div className="border-border bg-muted/40 text-copy-13 space-y-1 rounded-lg border p-4">
        <p className="text-foreground flex items-start gap-2">
          <Target className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>采纳率的分母只有一个来源</b>：18.4.3.1 的 AI 调用次数台账（只读）。 本模块只贡献
            <b>分子</b>。分母为 0 ⇒ 比率是<b>空值</b>，不是 0% ——
            「没有调用记录」与「调用了但没人用」是两件完全不同的事。
          </span>
        </p>
        <p className="text-muted-foreground flex items-start gap-2">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>本模块只出建议，不执行下线</b>：本仓没有按能力粒度的 AI 功能开关 ⇒
            决策端点只记录「采纳 / 驳回」，<b>下线动作需人工执行</b>。
          </span>
        </p>
        <p className="text-muted-foreground flex items-start gap-2">
          <Gauge className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>助手答案这一维如实答不可用</b>：上游 18.4.1.1 对话助手已熔断未交付 ⇒
            本模块只提供反馈登记的<b>载体</b>，<b>不伪造</b>助手的使用 / 反馈数据。
          </span>
        </p>
      </div>

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive text-copy-13 flex items-start gap-2 rounded-lg border p-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          {error}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-foreground text-copy-13 rounded-lg border p-3">
          {notice}
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value as Tab)}
        aria-label="AI 效果评估视图"
        options={[
          { value: 'overview', label: '效果总览' },
          { value: 'report', label: '采纳率报表' },
          { value: 'adoptions', label: '采纳记录' },
          { value: 'feedback', label: '有用性反馈' },
          { value: 'backtests', label: '预测回测' },
          { value: 'suggestions', label: '改进建议' },
          { value: 'thresholds', label: '评估阈值' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ================================================================ 效果总览 */}
      {tab === 'overview' && (
        <div className="space-y-4">
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="采纳率（窗口内）"
              value={formatRate(adoptionSummary?.adoptionRate ?? null)}
            />
            <StatCard
              label="低价值功能"
              value={formatCount(adoptionSummary?.lowValueFeatures ?? 0)}
              unit="个"
            />
            <StatCard label="预测 MAPE" value={formatRate(forecastSummary?.mape ?? null)} />
            <StatCard
              label="有用性反馈"
              value={formatCount(usefulnessSummary?.total ?? 0)}
              unit="条"
            />
          </div>

          <SectionCard
            title="采纳率"
            description="🔴 **分子 / 分母 / 比率 / 口径四件齐全**。分母来自 18.4.3.1 的调用次数台账，分子来自本模块的采纳台账。"
          >
            <div className="space-y-1">
              <div className="text-copy-13 flex items-center justify-between">
                <span>分母（AI 调用次数）</span>
                <span>{formatCount(adoptionSummary?.calls ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>分子（被采纳次数，含修改后采用）</span>
                <span>{formatCount(adoptionSummary?.adoptedCount ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>拒绝次数</span>
                <span>{formatCount(adoptionSummary?.rejectedCount ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>采纳率</span>
                <span className="text-foreground font-medium">
                  {formatRate(adoptionSummary?.adoptionRate ?? null)}
                </span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>已评估功能数</span>
                <span>{formatCount(adoptionSummary?.featuresEvaluated ?? 0)}</span>
              </div>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-3">
              分母来源：{adoptionSummary?.denominatorSource}
            </p>
            <p className="text-copy-13 text-muted-foreground mt-1">
              分子来源：{adoptionSummary?.numeratorSource}
            </p>
            <p className="text-copy-13 text-muted-foreground mt-1">{adoptionSummary?.basis}</p>
          </SectionCard>

          <SectionCard
            title="预测准确度回测"
            description="🔴 **实际值来自真实销量**（18.4.2.2），绝不拿预测值自己当实际值；该 SKU 一条销量都没有 ⇒ 如实记「缺实际值来源」，不记 0。"
          >
            <div className="space-y-1">
              <div className="text-copy-13 flex items-center justify-between">
                <span>评估条数</span>
                <span>{formatCount(forecastSummary?.evaluatedCount ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>实际可比条数</span>
                <span>{formatCount(forecastSummary?.comparedCount ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>缺实际值来源</span>
                <span>{formatCount(forecastSummary?.noActualSourceCount ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>MAPE（平均绝对百分比误差）</span>
                <span className="text-foreground font-medium">
                  {formatRate(forecastSummary?.mape ?? null)}
                </span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>命中区间率</span>
                <span className="text-foreground font-medium">
                  {formatRate(forecastSummary?.hitRate ?? null)}
                </span>
              </div>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-3">{forecastSummary?.basis}</p>
          </SectionCard>

          <SectionCard
            title="有用性反馈"
            description="三档：有用 / 部分有用 / 无用。🔴 助手答案维度上游未交付 ⇒ 该维度聚合恒为「暂无反馈」，页面不伪造助手数据。"
          >
            <div className="space-y-1">
              <div className="text-copy-13 flex items-center justify-between">
                <span>反馈总数</span>
                <span>{formatCount(usefulnessSummary?.total ?? 0)}</span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>有用 / 部分有用 / 无用</span>
                <span>
                  {formatCount(usefulnessSummary?.usefulCount ?? 0)} /{' '}
                  {formatCount(usefulnessSummary?.partiallyUsefulCount ?? 0)} /{' '}
                  {formatCount(usefulnessSummary?.notUsefulCount ?? 0)}
                </span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>有用率 / 部分有用率 / 无用率</span>
                <span>
                  {formatRate(usefulnessSummary?.usefulRate ?? null)} /{' '}
                  {formatRate(usefulnessSummary?.partiallyUsefulRate ?? null)} /{' '}
                  {formatRate(usefulnessSummary?.notUsefulRate ?? null)}
                </span>
              </div>
              <div className="text-copy-13 flex items-center justify-between">
                <span>正向率（有用 + 部分有用）</span>
                <span className="text-foreground font-medium">
                  {formatRate(usefulnessSummary?.positiveRate ?? null)}
                </span>
              </div>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-3">{usefulnessSummary?.basis}</p>
          </SectionCard>

          <SectionCard
            title="低价值功能清单"
            description="🔴 **样本不足（调用次数未达门槛）不下低价值结论** —— 不拿 3 次调用判死刑。"
          >
            {overview?.lowValueFeatures.length ? (
              <div className="space-y-2">
                {overview.lowValueFeatures.map((feature) => (
                  <div
                    key={`${feature.capabilityCode}-${feature.featureCode}`}
                    className="border-border text-copy-13 flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {feature.capabilityCode} · {feature.featureCode}
                      </div>
                      <div className="text-muted-foreground">{feature.reason}</div>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-muted-foreground">
                        调用 {formatCount(feature.calls)} 次 · 采纳率{' '}
                        {formatRate(feature.adoptionRate)}
                      </span>
                      <Badge tone={BADGE_TONE[verdictTone(feature.verdict)]}>
                        {feature.verdictLabel || verdictLabel(feature.verdict)}
                      </Badge>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<Target className="h-5 w-5" />}
                title="窗口内没有低价值功能"
                description="判定低价值要求样本量达标（调用次数 ≥ 门槛）—— 样本不足的功能只会出现在「数据不足」里，不在这里。"
              />
            )}
          </SectionCard>

          <SectionCard title="评估口径" description="下游取数时按这段口径对齐。">
            <p className="text-copy-13 text-muted-foreground">{overview?.basis}</p>
            <p className="text-copy-13 text-muted-foreground mt-2">
              窗口：{overview?.windowStart} 起 → {overview?.windowEnd} 止
            </p>
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 采纳率报表 */}
      {tab === 'report' && (
        <div className="space-y-4">
          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={capabilityFilter}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => {
                    setCapabilityFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadReport();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`采纳率报表（按功能，共 ${formatCount(report?.total ?? 0)} 行）`}
            description="粒度 = 能力 × 子功能。🔴 分子里的「已关联 / 未关联调用记录」**分开列**，两者单位可能不同，混算会得出假的比率。"
          >
            {report?.items.length ? (
              <div className="space-y-2">
                {report.items.map((row) => (
                  <div
                    key={`${row.capabilityCode}-${row.featureCode}`}
                    className="border-border text-copy-13 rounded-md border p-3"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-foreground font-medium">
                        {row.capabilityCode} · {row.featureCode || '（未指定子功能）'}
                      </span>
                      <Badge tone={BADGE_TONE[verdictTone(row.verdict)]}>
                        {row.verdictLabel || verdictLabel(row.verdict)}
                      </Badge>
                    </div>
                    <div className="mt-2 space-y-1">
                      {adoptionRateFacts(row).map((fact) => (
                        <div key={fact.label} className="flex items-start justify-between gap-3">
                          <span className="text-muted-foreground shrink-0">{fact.label}</span>
                          <span className="text-foreground break-all text-right">{fact.value}</span>
                        </div>
                      ))}
                    </div>
                    {row.reason ? <p className="text-muted-foreground mt-2">{row.reason}</p> : null}
                    <p className="text-muted-foreground mt-1">
                      窗口：{row.windowStart} 起 → {row.windowEnd} 止
                    </p>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<CircleAlert className="h-5 w-5" />}
                title={capabilityFilter ? '没有匹配的功能' : '报表还是空的'}
                description={
                  capabilityFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '上游 AI 能力回报调用之后，这里才会有分母；在此之前比率是空值。'
                }
              />
            )}
            {report && report.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(report.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= report.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 采纳记录 */}
      {tab === 'adoptions' && (
        <div className="space-y-4">
          <SectionCard
            title="录入一次人工处置"
            description="🔴 **主体成员由服务端从已认证会话解析**（请求体里没有 membershipId）。「修改后采用」**也算被采纳** —— 只记一个布尔值会把「改了才用」误判成「没用」。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码（必填）</span>
                <Input
                  value={adoptionForm.capabilityCode}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) =>
                    setAdoptionForm((f) => ({ ...f, capabilityCode: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">子功能</span>
                <Input
                  value={adoptionForm.featureCode}
                  placeholder="BATCH_TRANSLATE"
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, featureCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">处置</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={adoptionForm.decision}
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, decision: e.target.value }))}
                >
                  {DECISIONS.map((d) => (
                    <option key={d.value} value={d.value}>
                      {d.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物类型</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={adoptionForm.artifactType}
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, artifactType: e.target.value }))}
                >
                  {ARTIFACT_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物 id（译文 / 预测必填）</span>
                <Input
                  value={adoptionForm.artifactId}
                  placeholder="UUID"
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, artifactId: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物版本号</span>
                <Input
                  value={adoptionForm.artifactVersion}
                  placeholder="1"
                  onChange={(e) =>
                    setAdoptionForm((f) => ({ ...f, artifactVersion: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物定位码</span>
                <Input
                  value={adoptionForm.artifactRef}
                  placeholder="SKU-1001 / 文案编号"
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, artifactRef: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">关联调用记录 id（可空）</span>
                <Input
                  value={adoptionForm.usageRecordId}
                  placeholder="UUID"
                  onChange={(e) =>
                    setAdoptionForm((f) => ({ ...f, usageRecordId: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">修改说明</span>
                <Input
                  value={adoptionForm.editNote}
                  placeholder="改了术语与语气"
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, editNote: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">驳回原因（拒绝时必填）</span>
                <Input
                  value={adoptionForm.rejectReason}
                  placeholder="译文与原文语义不符"
                  onChange={(e) => setAdoptionForm((f) => ({ ...f, rejectReason: e.target.value }))}
                />
              </label>
            </div>
            <div className="mt-3">
              <Button size="sm" onClick={() => void submitAdoption()} disabled={busy}>
                登记处置
              </Button>
            </div>
          </SectionCard>

          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={capabilityFilter}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => {
                    setCapabilityFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">子功能</span>
                <Input
                  value={featureFilter}
                  placeholder="BATCH_TRANSLATE"
                  onChange={(e) => {
                    setFeatureFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">处置</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={decisionFilter}
                  onChange={(e) => {
                    setDecisionFilter(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部</option>
                  {DECISIONS.map((d) => (
                    <option key={d.value} value={d.value}>
                      {d.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadAdoptions();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`采纳记录台账（只追加，共 ${formatCount(adoptions?.total ?? 0)} 行）`}
            description="事实不可改写，但填错了可以**撤回**（ACTIVE → WITHDRAWN）—— 一条填错的采纳记录会让采纳率永远偏。"
          >
            {adoptions?.items.length ? (
              <div className="space-y-2">
                {adoptions.items.map((row) => (
                  <div
                    key={row.id}
                    className="border-border text-copy-13 flex flex-wrap items-start justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {row.capabilityCode} · {row.featureCode || '（未指定子功能）'}
                      </div>
                      <div className="text-muted-foreground">
                        {row.artifactTypeLabel || artifactTypeLabel(row.artifactType)}
                        {row.artifactRef ? ` · ${row.artifactRef}` : ''}
                        {row.artifactVersion ? ` · v${row.artifactVersion}` : ''}
                      </div>
                      <div className="text-muted-foreground">
                        成员 {memberLabel(row.membershipId)} · {formatInstant(row.occurredAt)}
                      </div>
                      {row.editNote ? (
                        <div className="text-muted-foreground">修改说明：{row.editNote}</div>
                      ) : null}
                      {row.rejectReason ? (
                        <div className="text-muted-foreground">驳回原因：{row.rejectReason}</div>
                      ) : null}
                      {row.withdrawnReason ? (
                        <div className="text-muted-foreground">撤回原因：{row.withdrawnReason}</div>
                      ) : null}
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={BADGE_TONE[adoptionDecisionTone(row.decision)]}>
                        {row.decisionLabel || adoptionDecisionLabel(row.decision)}
                      </Badge>
                      <Badge tone={BADGE_TONE[factStatusTone(row.status)]}>
                        {factStatusLabel(row.status)}
                      </Badge>
                      {row.status === 'ACTIVE' ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void doWithdrawAdoption(row.id)}
                        >
                          撤回
                        </Button>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<CircleAlert className="h-5 w-5" />}
                title={
                  capabilityFilter || featureFilter || decisionFilter
                    ? '没有匹配的记录'
                    : '台账还是空的'
                }
                description={
                  capabilityFilter || featureFilter || decisionFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '人工处置一条之后这里会逐行追加（**只追加，不许改也不许删**）。'
                }
              />
            )}
            {adoptions && adoptions.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(adoptions.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= adoptions.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 有用性反馈 */}
      {tab === 'feedback' && (
        <div className="space-y-4">
          <SectionCard
            title="登记一次有用性反馈"
            description="🔴 **主体成员由服务端从已认证会话解析**。理由码是**受控词表**（可聚合），补充说明是自由文本（**不参与统计口径**）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码（必填）</span>
                <Input
                  value={feedbackForm.capabilityCode}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) =>
                    setFeedbackForm((f) => ({ ...f, capabilityCode: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">子功能</span>
                <Input
                  value={feedbackForm.featureCode}
                  placeholder="BATCH_TRANSLATE"
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, featureCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">有用性</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={feedbackForm.usefulness}
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, usefulness: e.target.value }))}
                >
                  {USEFULNESS.map((u) => (
                    <option key={u.value} value={u.value}>
                      {u.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">理由码（可空）</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={feedbackForm.reasonCode}
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, reasonCode: e.target.value }))}
                >
                  <option value="">未选理由</option>
                  {Object.entries(FEEDBACK_REASON_LABELS).map(([code, label]) => (
                    <option key={code} value={code}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物类型</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={feedbackForm.artifactType}
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, artifactType: e.target.value }))}
                >
                  {ARTIFACT_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物 id（译文 / 预测必填）</span>
                <Input
                  value={feedbackForm.artifactId}
                  placeholder="UUID"
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, artifactId: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物版本号</span>
                <Input
                  value={feedbackForm.artifactVersion}
                  placeholder="1"
                  onChange={(e) =>
                    setFeedbackForm((f) => ({ ...f, artifactVersion: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">产物定位码</span>
                <Input
                  value={feedbackForm.artifactRef}
                  placeholder="SKU-1001 / 文案编号"
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, artifactRef: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">补充说明（不参与统计口径）</span>
                <Input
                  value={feedbackForm.reasonText}
                  placeholder="译文漏了整段免责说明"
                  onChange={(e) => setFeedbackForm((f) => ({ ...f, reasonText: e.target.value }))}
                />
              </label>
            </div>
            <div className="mt-3">
              <Button size="sm" onClick={() => void submitFeedback()} disabled={busy}>
                登记反馈
              </Button>
            </div>
          </SectionCard>

          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={capabilityFilter}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => {
                    setCapabilityFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">子功能</span>
                <Input
                  value={featureFilter}
                  placeholder="BATCH_TRANSLATE"
                  onChange={(e) => {
                    setFeatureFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">有用性</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={usefulnessFilter}
                  onChange={(e) => {
                    setUsefulnessFilter(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部</option>
                  {USEFULNESS.map((u) => (
                    <option key={u.value} value={u.value}>
                      {u.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadFeedback();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`有用性反馈台账（只追加，共 ${formatCount(feedback?.total ?? 0)} 行）`}
            description="一次「这个答案有用吗」的反馈一行。"
          >
            {feedback?.items.length ? (
              <div className="space-y-2">
                {feedback.items.map((row) => (
                  <div
                    key={row.id}
                    className="border-border text-copy-13 flex flex-wrap items-start justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {row.capabilityCode} · {row.featureCode || '（未指定子功能）'}
                      </div>
                      <div className="text-muted-foreground">
                        {row.artifactTypeLabel || artifactTypeLabel(row.artifactType)}
                        {row.artifactRef ? ` · ${row.artifactRef}` : ''}
                        {row.reasonCodeLabel ? ` · ${row.reasonCodeLabel}` : ''}
                      </div>
                      <div className="text-muted-foreground">
                        成员 {memberLabel(row.membershipId)} · {formatInstant(row.occurredAt)}
                      </div>
                      {row.reasonText ? (
                        <div className="text-muted-foreground">补充：{row.reasonText}</div>
                      ) : null}
                      {row.withdrawnReason ? (
                        <div className="text-muted-foreground">撤回原因：{row.withdrawnReason}</div>
                      ) : null}
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge tone={BADGE_TONE[usefulnessTone(row.usefulness)]}>
                        {row.usefulnessLabel || usefulnessLabel(row.usefulness)}
                      </Badge>
                      <Badge tone={BADGE_TONE[factStatusTone(row.status)]}>
                        {factStatusLabel(row.status)}
                      </Badge>
                      {row.status === 'ACTIVE' ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void doWithdrawFeedback(row.id)}
                        >
                          撤回
                        </Button>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<CircleAlert className="h-5 w-5" />}
                title={
                  capabilityFilter || featureFilter || usefulnessFilter
                    ? '没有匹配的反馈'
                    : '台账还是空的'
                }
                description={
                  capabilityFilter || featureFilter || usefulnessFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '登记一次有用性反馈之后这里会逐行追加。'
                }
              />
            )}
            {feedback && feedback.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(feedback.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= feedback.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 预测回测 */}
      {tab === 'backtests' && (
        <div className="space-y-4">
          <SectionCard
            title="跑一次预测准确度回测"
            description="🔴 **实际值来自真实销量**（18.4.2.2 的销量历史），绝不拿预测值自己当实际值。该 SKU 一条销量都没有 ⇒ 如实记「缺实际值来源」，**不记 0**。回测是计算快照（真实销量是后补的）⇒ 重跑会刷新同一行。"
          >
            <Button size="sm" onClick={() => void doRunBacktest()} disabled={busy}>
              <RefreshCw className="h-3.5 w-3.5" />
              跑回测（取最近一次预测运行）
            </Button>
          </SectionCard>

          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">SKU 编码</span>
                <Input
                  value={skuFilter}
                  placeholder="SKU-1001"
                  onChange={(e) => {
                    setSkuFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">回测判定</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部</option>
                  {BACKTEST_STATUS.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadBacktests();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`回测结果台账（共 ${formatCount(backtests?.total ?? 0)} 行）`}
            description="一条预测结果一行：预测值 + 置信区间、**实际值**、绝对误差、相对误差、是否落在区间内。"
          >
            {backtests?.items.length ? (
              <div className="space-y-2">
                {backtests.items.map((row) => (
                  <div key={row.id} className="border-border text-copy-13 rounded-md border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-foreground font-medium">
                        {row.skuCode} · {row.skuName || '（无名称）'}
                      </span>
                      <div className="flex items-center gap-2">
                        <Badge tone={BADGE_TONE[backtestStatusTone(row.status)]}>
                          {row.statusLabel || row.status}
                        </Badge>
                        {row.withinInterval === true ? (
                          <Badge tone="success">落在区间内</Badge>
                        ) : null}
                        {row.withinInterval === false ? (
                          <Badge tone="warning">落在区间外</Badge>
                        ) : null}
                      </div>
                    </div>
                    <div className="mt-2 space-y-1">
                      <div className="flex items-start justify-between gap-3">
                        <span className="text-muted-foreground">预测值（区间）</span>
                        <span className="text-foreground text-right">
                          {trimDecimal(row.forecastPoint)}（{trimDecimal(row.forecastLower)} ~{' '}
                          {trimDecimal(row.forecastUpper)}）
                        </span>
                      </div>
                      <div className="flex items-start justify-between gap-3">
                        <span className="text-muted-foreground">实际值</span>
                        <span className="text-foreground text-right">
                          {row.actualUnits === null
                            ? '—（缺实际值来源）'
                            : trimDecimal(row.actualUnits)}
                        </span>
                      </div>
                      <div className="flex items-start justify-between gap-3">
                        <span className="text-muted-foreground">绝对误差 / 相对误差</span>
                        <span className="text-foreground text-right">
                          {trimDecimal(row.absoluteError)} / {trimDecimal(row.relativeError)}
                        </span>
                      </div>
                      <div className="flex items-start justify-between gap-3">
                        <span className="text-muted-foreground">实际值来源</span>
                        <span className="text-foreground break-all text-right">
                          {row.actualSource}（{formatCount(row.actualRecordDays)} 天销量）
                        </span>
                      </div>
                    </div>
                    {row.note ? <p className="text-muted-foreground mt-2">{row.note}</p> : null}
                    <p className="text-muted-foreground mt-1">
                      运行 {row.runNo} · {formatInstant(row.evaluatedAt)}
                    </p>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<Gauge className="h-5 w-5" />}
                title={skuFilter || statusFilter ? '没有匹配的回测结果' : '还没有回测结果'}
                description={
                  skuFilter || statusFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '点上面的「跑回测」：实际值取自真实销量，没有销量的 SKU 会如实记「缺实际值来源」。'
                }
              />
            )}
            {backtests && backtests.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(backtests.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= backtests.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 改进建议 */}
      {tab === 'suggestions' && (
        <div className="space-y-4">
          <SectionCard
            title="生成改进建议"
            description="按采纳率报表与阈值生成：低价值 ⇒ 建议下线；样本不足 ⇒ 数据缺口（**不是**低价值）。🔴 **只出建议清单**，不执行任何下线动作。"
          >
            <Button size="sm" onClick={() => void doGenerateSuggestions()} disabled={busy}>
              <RefreshCw className="h-3.5 w-3.5" />
              生成建议
            </Button>
          </SectionCard>

          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">状态</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部</option>
                  {SUGGESTION_STATUS.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">建议类型</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={suggestionTypeFilter}
                  onChange={(e) => {
                    setSuggestionTypeFilter(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部</option>
                  {SUGGESTION_TYPES.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={capabilityFilter}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => {
                    setCapabilityFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadSuggestions();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`改进建议清单（共 ${formatCount(suggestions?.total ?? 0)} 条，其中低价值 ${formatCount(suggestions?.lowValueCount ?? 0)} 条）`}
            description="每条建议都带齐四件事：针对哪个功能、当前指标是多少、建议做什么、依据是什么。"
          >
            {suggestions?.items.length ? (
              <div className="space-y-2">
                {suggestions.items.map((row) => (
                  <div key={row.id} className="border-border text-copy-13 rounded-md border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-foreground font-medium">{row.title}</span>
                      <div className="flex items-center gap-2">
                        <Badge tone={BADGE_TONE[suggestionTypeTone(row.suggestionType)]}>
                          {row.suggestionTypeLabel || suggestionTypeLabel(row.suggestionType)}
                        </Badge>
                        <Badge tone={BADGE_TONE[suggestionSeverityTone(row.severity)]}>
                          {row.severityLabel || row.severity}
                        </Badge>
                        <Badge tone={BADGE_TONE[suggestionStatusTone(row.status)]}>
                          {row.statusLabel || suggestionStatusLabel(row.status)}
                        </Badge>
                      </div>
                    </div>
                    <div className="mt-2 space-y-1">
                      {suggestionFacts(row).map((fact) => (
                        <div key={fact.label} className="flex items-start justify-between gap-3">
                          <span className="text-muted-foreground shrink-0">{fact.label}</span>
                          <span className="text-foreground break-all text-right">{fact.value}</span>
                        </div>
                      ))}
                    </div>
                    <p className="text-muted-foreground mt-2">
                      责任方：{row.actionOwner || '—'} · 窗口 {row.windowStart} → {row.windowEnd}
                    </p>
                    {row.offlineAction ? (
                      <p className="text-muted-foreground mt-1">下线动作：{row.offlineAction}</p>
                    ) : null}
                    {row.decisionNote ? (
                      <p className="text-muted-foreground mt-1">决策说明：{row.decisionNote}</p>
                    ) : null}
                    {row.decidedAt ? (
                      <p className="text-muted-foreground mt-1">
                        决策于 {formatInstant(row.decidedAt)} · 决策人 {memberLabel(row.decidedBy)}
                      </p>
                    ) : null}
                    {row.status === 'OPEN' ? (
                      <div className="mt-2 flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          disabled={busy}
                          onClick={() => void doDecide(row.id, 'ACCEPTED')}
                        >
                          采纳建议
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void doDecide(row.id, 'REJECTED')}
                        >
                          驳回建议
                        </Button>
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<Target className="h-5 w-5" />}
                title={
                  statusFilter || suggestionTypeFilter || capabilityFilter
                    ? '没有匹配的建议'
                    : '还没有改进建议'
                }
                description={
                  statusFilter || suggestionTypeFilter || capabilityFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '点上面的「生成建议」：按采纳率报表与阈值生成低价值清单与数据缺口。'
                }
              />
            )}
            {suggestions && suggestions.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(suggestions.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= suggestions.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 评估阈值 */}
      {tab === 'thresholds' && (
        <div className="space-y-4">
          <SectionCard
            title="当前生效的评估阈值"
            description="「什么算低价值」是**业务口径** ⇒ 归租户维护（一个租户只有一行）。🔴 库里没登记时返回**系统默认阈值**并标「未配置」—— 不谎称是租户定的。"
          >
            {thresholds ? (
              <>
                <div className="mb-2">
                  <Badge tone={thresholds.configured ? 'success' : 'neutral'}>
                    {thresholds.configured ? '本租户已登记' : '系统默认值（本租户未登记）'}
                  </Badge>
                </div>
                <div className="space-y-1">
                  {thresholdFacts(thresholds).map((fact) => (
                    <div
                      key={fact.label}
                      className="text-copy-13 flex items-center justify-between"
                    >
                      <span className="text-muted-foreground">{fact.label}</span>
                      <span className="text-foreground font-medium">{fact.value}</span>
                    </div>
                  ))}
                </div>
                <p className="text-copy-13 text-muted-foreground mt-3">{thresholds.basis}</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  更新于 {formatInstant(thresholds.updatedAt)}
                </p>
              </>
            ) : (
              <EmptyState title="还没有阈值" description="重新加载一次即可取到系统默认阈值。" />
            )}
          </SectionCard>

          <SectionCard
            title="登记 / 修改阈值"
            description="🔴 **留空的字段沿用当前值**（不是「清零」）。判定「样本不足」的门槛很重要 —— **不拿 3 次调用判死刑**。比率必须是 0 ~ 1 的十进制数（如 0.2）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">调用次数门槛（留空 = 沿用）</span>
                <Input
                  value={thresholdForm.minCalls}
                  placeholder="20"
                  onChange={(e) => setThresholdForm((f) => ({ ...f, minCalls: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">低价值采纳率阈值（留空 = 沿用）</span>
                <Input
                  value={thresholdForm.lowAdoptionRate}
                  placeholder="0.2"
                  onChange={(e) =>
                    setThresholdForm((f) => ({ ...f, lowAdoptionRate: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">反馈数门槛（留空 = 沿用）</span>
                <Input
                  value={thresholdForm.minFeedbackCount}
                  placeholder="5"
                  onChange={(e) =>
                    setThresholdForm((f) => ({ ...f, minFeedbackCount: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">低有用性阈值（留空 = 沿用）</span>
                <Input
                  value={thresholdForm.lowUsefulRate}
                  placeholder="0.5"
                  onChange={(e) =>
                    setThresholdForm((f) => ({ ...f, lowUsefulRate: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">MAPE 阈值（留空 = 沿用）</span>
                <Input
                  value={thresholdForm.maxMape}
                  placeholder="0.3"
                  onChange={(e) => setThresholdForm((f) => ({ ...f, maxMape: e.target.value }))}
                />
              </label>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="sm" onClick={() => void submitThreshold()} disabled={busy}>
                保存阈值
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setThresholdForm(EMPTY_THRESHOLD)}
                disabled={busy}
              >
                清空表单
              </Button>
            </div>
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="逐维说清「有没有、依据是什么、缺了归谁」。`available=false` 的项**按原样显示**并写明归谁 —— 不显示成「暂无」。"
          >
            <div className="space-y-2">
              {capabilities?.capabilities.map((cap) => (
                <div key={cap.name} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-foreground font-medium">{cap.name}</span>
                    <Badge tone={cap.available ? 'success' : 'danger'}>
                      {cap.available ? '已交付' : '未交付'}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground text-copy-13 mt-1">{cap.basis}</p>
                  {cap.ownerModule ? (
                    <p className="text-muted-foreground text-copy-13 mt-1">
                      归 {cap.ownerModule} 模块；影响：{cap.impact}
                    </p>
                  ) : null}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="缺口" description="谁没交付、归谁、缺了会怎样。">
            <div className="space-y-2">
              {capabilities?.downstreamGaps.map((gap) => (
                <div key={gap.name} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-foreground font-medium">
                      {gap.module} · {gap.name}
                    </span>
                    <Badge tone="warning">{gap.group}</Badge>
                  </div>
                  <p className="text-muted-foreground text-copy-13 mt-1">{gap.detail}</p>
                </div>
              ))}
            </div>
          </SectionCard>

          {unavailable.length ? (
            <SectionCard
              title="当前未交付的评估对象"
              description="🔴 这些维度**无法评估** —— 页面不会用别的数据冒充它们。"
            >
              <div className="space-y-1">
                {unavailable.map((cap) => (
                  <div
                    key={cap.name}
                    className="text-copy-13 flex items-center justify-between gap-2"
                  >
                    <span>{cap.name}</span>
                    <Badge tone="danger">未交付（归 {cap.ownerModule || '—'}）</Badge>
                  </div>
                ))}
              </div>
            </SectionCard>
          ) : null}
        </div>
      )}
    </div>
  );
}
