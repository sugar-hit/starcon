'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { AlertTriangle, Gavel, RefreshCw, ShieldCheck } from 'lucide-react';
import type {
  CrmNegativeReviewActionRow,
  CrmNegativeReviewAppealRow,
  CrmNegativeReviewCandidatePage,
  CrmNegativeReviewCapabilities,
  CrmNegativeReviewCasePage,
  CrmNegativeReviewCaseRow,
  CrmNegativeReviewDetail,
  CrmNegativeReviewRulePage,
  CrmNegativeReviewRuleRow,
  CrmNegativeReviewShopView,
  CrmNegativeReviewStats,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  actionTypeLabel,
  appealStatusLabel,
  appealTypeLabel,
  cancelNegativeReviewAppeal,
  caseStatusLabel,
  causeLabel,
  createNegativeReviewAppeal,
  createNegativeReviewCase,
  createNegativeReviewRule,
  classifyNegativeReviewCase,
  fetchNegativeReviewActions,
  fetchNegativeReviewAppeals,
  fetchNegativeReviewCapabilities,
  fetchNegativeReviewCase,
  fetchNegativeReviewCases,
  fetchNegativeReviewCandidates,
  fetchNegativeReviewRules,
  fetchNegativeReviewShops,
  fetchNegativeReviewStats,
  markNegativeReviewAppealSubmitted,
  outcomeLabel,
  recordNegativeReviewAction,
  recordNegativeReviewAppealOutcome,
  resolutionRateText,
  resolutionStatusLabel,
  ruleScopeLabel,
  submitNegativeReviewAppeal,
  transportLabel,
  updateNegativeReviewRule,
} from './negative-review-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.2.1.3 差评处理工作区。
 *
 * <p>页面按「先看清边界、再看口径、最后动手」的顺序排，因为蓝图加粗句是
 * 「<b>解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额</b>」——
 * 界面上第一件要让人看到的就是**能力边界**与**解决率口径**，
 * 而不是一张可以随手点的差评清单。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台差评申诉接口、买家站内信发送、11 售后转交三块是 available=false，
 *       页面把它们<b>显式灰掉</b>并写清归属模块，不给任何看起来能点的假入口。</li>
 *   <li><b>处理规则</b> —— 原因分类与「能不能申诉」<b>真的可配</b>：
 *       逐平台的差评阈值、申诉支持、申诉配额，以及逐（平台 × 原因）的规则。
 *       没配策略的平台**建不了处理单**，页面据此提示先配置规则。</li>
 *   <li><b>待接手的差评</b> —— 还没建处理单的那些差评（来自 15.2.1.1 的评价列表）。
 *       没有这一段，第一张处理单永远建不出来。</li>
 *   <li><b>处理单清单</b> —— 真分页 + 真筛选（店铺 / 平台 / 原因 / 状态 / 解决状态 /
 *       可申诉 / 星级 / 关键字），空态区分「筛选无结果」与「还没有处理单」。</li>
 *   <li><b>处理单详情</b> —— 处理单本体 + <b>动作流水</b> + <b>申诉任务</b> +
 *       <b>关联订单上下文</b> + 当时用的规则。订单上下文读不到时显示
 *       「不可用」+ 原因，<b>绝不显示空白</b>。</li>
 *   <li><b>顺序闸要看得见</b>：没走完「联系买家」时，「请求修改 / 建申诉任务」
 *       显式禁用并说明原因，而不是让人点了再吃一个 409。</li>
 * </ul>
 */
export function NegativeReviewWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.negative_review.update');

  const [capabilities, setCapabilities] = useState<CrmNegativeReviewCapabilities | null>(null);
  const [shops, setShops] = useState<CrmNegativeReviewShopView[]>([]);
  const [cases, setCases] = useState<CrmNegativeReviewCasePage | null>(null);
  const [stats, setStats] = useState<CrmNegativeReviewStats | null>(null);
  const [candidates, setCandidates] = useState<CrmNegativeReviewCandidatePage | null>(null);
  const [rules, setRules] = useState<CrmNegativeReviewRulePage | null>(null);
  const [detail, setDetail] = useState<CrmNegativeReviewDetail | null>(null);

  // 处理单筛选
  const [shopId, setShopId] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [causeCode, setCauseCode] = useState('');
  const [caseStatus, setCaseStatus] = useState('');
  const [resolutionStatus, setResolutionStatus] = useState('');
  const [appealEligible, setAppealEligible] = useState('');
  const [rating, setRating] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [statsDays, setStatsDays] = useState(30);

  // 候选差评筛选
  const [candidateRating, setCandidateRating] = useState('');
  const [candidateKeyword, setCandidateKeyword] = useState('');

  // 分类
  const [classifyCause, setClassifyCause] = useState('LOGISTICS');
  const [classifyNote, setClassifyNote] = useState('');

  // 动作
  const [actionType, setActionType] = useState('CONTACT_BUYER');
  const [actionTransport, setActionTransport] = useState('MANUAL_HANDOFF');
  const [actionOutcome, setActionOutcome] = useState('SUCCEEDED');
  const [actionSummary, setActionSummary] = useState('');
  const [actionDetail, setActionDetail] = useState('');
  const [actionOccurredAt, setActionOccurredAt] = useState('');
  const [afterSalesReason, setAfterSalesReason] = useState('');

  // 申诉
  const [appealType, setAppealType] = useState('REMOVAL');
  const [appealReason, setAppealReason] = useState('');
  const [appealSubmittedAt, setAppealSubmittedAt] = useState('');
  const [appealReference, setAppealReference] = useState('');

  // 规则
  const [ruleScope, setRuleScope] = useState('PLATFORM');
  const [rulePlatform, setRulePlatform] = useState('');
  const [ruleCause, setRuleCause] = useState('LOGISTICS');
  const [ruleLabel, setRuleLabel] = useState('');
  const [ruleThreshold, setRuleThreshold] = useState('2');
  const [ruleAppealSupported, setRuleAppealSupported] = useState(true);
  const [ruleQuota, setRuleQuota] = useState('3');
  const [rulePeriod, setRulePeriod] = useState('MONTHLY');
  const [ruleEligible, setRuleEligible] = useState(true);
  const [ruleBasis, setRuleBasis] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const causeCodes = capabilities?.causeCodes ?? [];
  const caseStatuses = capabilities?.caseStatuses ?? [];
  const resolutionStatuses = capabilities?.resolutionStatuses ?? [];
  const actionTypes = capabilities?.actionTypes ?? [];
  const appealTypes = capabilities?.appealTypes ?? [];

  /** 当前是否有筛选条件 —— 空态要区分「筛选无结果」与「还没有处理单」。 */
  const hasFilter = useMemo(
    () =>
      Boolean(
        shopId ||
        platformCode ||
        causeCode ||
        caseStatus ||
        resolutionStatus ||
        appealEligible ||
        rating ||
        keyword,
      ),
    [
      shopId,
      platformCode,
      causeCode,
      caseStatus,
      resolutionStatus,
      appealEligible,
      rating,
      keyword,
    ],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        shopId: shopId || undefined,
        platformCode: platformCode || undefined,
        causeCode: causeCode || undefined,
        caseStatus: caseStatus || undefined,
        resolutionStatus: resolutionStatus || undefined,
        appealEligible: appealEligible === '' ? undefined : appealEligible === 'true',
        rating: rating ? Number(rating) : undefined,
        keyword: keyword || undefined,
        page: pageIndex,
        size: 20,
      };
      const [caps, shopList, casePage, caseStats, candidatePage, rulePage] = await Promise.all([
        fetchNegativeReviewCapabilities(),
        fetchNegativeReviewShops(),
        fetchNegativeReviewCases(params),
        fetchNegativeReviewStats(params, statsDays),
        fetchNegativeReviewCandidates({
          rating: candidateRating ? Number(candidateRating) : undefined,
          keyword: candidateKeyword || undefined,
          page: 0,
          size: 20,
        }),
        fetchNegativeReviewRules({ page: 0, size: 50 }),
      ]);
      setCapabilities(caps);
      setShops(shopList);
      setCases(casePage);
      setStats(caseStats);
      setCandidates(candidatePage);
      setRules(rulePage);
    } catch (e) {
      setError(errorText(e, '差评处理工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [
    shopId,
    platformCode,
    causeCode,
    caseStatus,
    resolutionStatus,
    appealEligible,
    rating,
    keyword,
    candidateRating,
    candidateKeyword,
    pageIndex,
    statsDays,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (caseId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchNegativeReviewCase(caseId));
    } catch (e) {
      setActionError(errorText(e, '处理单详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const createCase = useCallback(
    async (reviewId: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const created = await createNegativeReviewCase({ reviewId, note: '' });
        setNotice(
          `已建处理单 ${created.caseNo}（差评 ${created.reviewNo}，${created.reviewRating} 星）。`,
        );
        await load();
        await openDetail(created.id);
      } catch (e) {
        setActionError(errorText(e, '建处理单失败'));
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const submitClassify = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const updated = await classifyNegativeReviewCase(detail.negativeReview.id, {
        causeCode: classifyCause,
        causeNote: classifyNote,
      });
      setNotice(
        updated.appealEligible
          ? `已标记原因「${causeLabel(updated.causeCode ?? '')}」，判定结果：**可申诉**。`
          : `已标记原因「${causeLabel(updated.causeCode ?? '')}」，判定结果：**不可申诉**。`,
      );
      setClassifyNote('');
      await load();
      await openDetail(detail.negativeReview.id);
    } catch (e) {
      setActionError(errorText(e, '标记原因失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, classifyCause, classifyNote, load, openDetail]);

  const submitAction = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await recordNegativeReviewAction(detail.negativeReview.id, {
        actionType,
        transport: actionTransport,
        outcome: actionOutcome,
        outcomeNote: '',
        summary: actionSummary,
        detail: actionDetail,
        afterSalesReason: actionType === 'AFTER_SALES_HANDOFF' ? afterSalesReason : '',
        occurredAt: actionOccurredAt,
        operatorName: '',
      });
      setNotice(`已记录动作「${actionTypeLabel(created.actionType)}」。`);
      setActionSummary('');
      setActionDetail('');
      setAfterSalesReason('');
      setActionOccurredAt('');
      await load();
      await openDetail(detail.negativeReview.id);
    } catch (e) {
      // 🔴 平台侧未接入 ⇒ 409 且**一行都没写**：页面如实显示原因，
      //   不给一个「点了就成功」的假按钮。
      setActionError(errorText(e, '记录动作失败'));
    } finally {
      setBusy(false);
    }
  }, [
    detail,
    actionType,
    actionTransport,
    actionOutcome,
    actionSummary,
    actionDetail,
    afterSalesReason,
    actionOccurredAt,
    load,
    openDetail,
  ]);

  const submitAppeal = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createNegativeReviewAppeal(detail.negativeReview.id, {
        appealType,
        causeCode: detail.negativeReview.causeCode ?? '',
        reason: appealReason,
        content: '',
        note: '',
      });
      setNotice(
        `已建申诉任务 ${created.appealNo}（本周期第 ${created.quotaIndex}/${created.quotaLimit} 次）。`,
      );
      setAppealReason('');
      await load();
      await openDetail(detail.negativeReview.id);
    } catch (e) {
      setActionError(errorText(e, '建申诉任务失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, appealType, appealReason, load, openDetail]);

  const submitToPlatform = useCallback(
    async (appeal: CrmNegativeReviewAppealRow) => {
      if (!detail) return;
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await submitNegativeReviewAppeal(detail.negativeReview.id, appeal.id);
        setNotice('已提交申诉。');
        await load();
        await openDetail(detail.negativeReview.id);
      } catch (e) {
        // 🔴 平台差评申诉适配器未接入 ⇒ 恒 409 且一行都没写。
        setActionError(
          errorText(e, '平台申诉接口未接入：本次没有写入任何数据，申诉任务仍是「待提交」'),
        );
      } finally {
        setBusy(false);
      }
    },
    [detail, load, openDetail],
  );

  const markSubmitted = useCallback(
    async (appeal: CrmNegativeReviewAppealRow) => {
      if (!detail) return;
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await markNegativeReviewAppealSubmitted(detail.negativeReview.id, appeal.id, {
          submittedAt: appealSubmittedAt,
          platformReference: appealReference,
          note: '运营在平台侧实际提交后回填',
        });
        setNotice(`已登记 ${appeal.appealNo} 的平台侧提交时刻。`);
        setAppealSubmittedAt('');
        setAppealReference('');
        await load();
        await openDetail(detail.negativeReview.id);
      } catch (e) {
        setActionError(errorText(e, '登记平台侧提交失败'));
      } finally {
        setBusy(false);
      }
    },
    [detail, appealSubmittedAt, appealReference, load, openDetail],
  );

  const recordOutcome = useCallback(
    async (appeal: CrmNegativeReviewAppealRow, result: 'APPROVED' | 'REJECTED') => {
      if (!detail) return;
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await recordNegativeReviewAppealOutcome(detail.negativeReview.id, appeal.id, {
          result,
          decidedAt: appealSubmittedAt,
          platformReceipt: '',
          reason: result === 'APPROVED' ? '平台认定评价与事实不符' : '平台维持原评价',
        });
        setNotice(
          `已登记 ${appeal.appealNo} 的平台判定（${result === 'APPROVED' ? '通过' : '驳回'}）。`,
        );
        setAppealSubmittedAt('');
        await load();
        await openDetail(detail.negativeReview.id);
      } catch (e) {
        setActionError(errorText(e, '登记平台判定失败'));
      } finally {
        setBusy(false);
      }
    },
    [detail, appealSubmittedAt, load, openDetail],
  );

  const cancelAppeal = useCallback(
    async (appeal: CrmNegativeReviewAppealRow) => {
      if (!detail) return;
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await cancelNegativeReviewAppeal(detail.negativeReview.id, appeal.id, {
          reason: '运营撤回（配额归还）',
        });
        setNotice(`已撤回 ${appeal.appealNo}，申诉配额已归还。`);
        await load();
        await openDetail(detail.negativeReview.id);
      } catch (e) {
        setActionError(errorText(e, '撤回申诉失败'));
      } finally {
        setBusy(false);
      }
    },
    [detail, load, openDetail],
  );

  const submitRule = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createNegativeReviewRule({
        ruleScope,
        platformCode: rulePlatform,
        causeCode: ruleScope === 'CAUSE' ? ruleCause : '',
        label:
          ruleLabel || (ruleScope === 'PLATFORM' ? `${rulePlatform} 策略` : `${ruleCause} 规则`),
        description: '',
        negativeRatingThreshold: Number(ruleThreshold),
        appealSupported: ruleScope === 'PLATFORM' ? ruleAppealSupported : false,
        contactBuyerRequired: true,
        appealQuotaLimit: ruleScope === 'PLATFORM' ? Number(ruleQuota) : 0,
        quotaPeriod: rulePeriod,
        appealWindowDays: 30,
        appealEligible: ruleScope === 'CAUSE' ? ruleEligible : false,
        appealBasisTemplate: ruleScope === 'CAUSE' ? ruleBasis : '',
        evidenceRequired: false,
        priority: 100,
        note: '',
      });
      setNotice(`已建规则 ${created.ruleNo}（版本 ${created.version}）。`);
      setRuleLabel('');
      setRuleBasis('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '建规则失败'));
    } finally {
      setBusy(false);
    }
  }, [
    ruleScope,
    rulePlatform,
    ruleCause,
    ruleLabel,
    ruleThreshold,
    ruleAppealSupported,
    ruleQuota,
    rulePeriod,
    ruleEligible,
    ruleBasis,
    load,
  ]);

  /**
   * 改一条规则的申诉配额。
   *
   * <p>⚠️ 契约里规则更新入参是**全字段必填**（改动语义是「原地改一版」，
   * 不是 PATCH）⇒ 这里必须把**其余字段原样回填**，不能只传配额。
   */
  const bumpQuota = useCallback(
    async (rule: CrmNegativeReviewRuleRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const updated = await updateNegativeReviewRule(rule.id, {
          label: rule.label,
          description: rule.description ?? '',
          negativeRatingThreshold: rule.negativeRatingThreshold,
          appealSupported: rule.appealSupported,
          contactBuyerRequired: rule.contactBuyerRequired,
          appealQuotaLimit: rule.appealQuotaLimit + 1,
          quotaPeriod: rule.quotaPeriod,
          appealWindowDays: rule.appealWindowDays,
          appealEligible: rule.appealEligible,
          appealBasisTemplate: rule.appealBasisTemplate ?? '',
          evidenceRequired: rule.evidenceRequired,
          priority: rule.priority ?? 100,
          status: rule.status,
          note: rule.note ?? '',
        });
        setNotice(`已更新规则 ${updated.ruleNo}（版本 ${updated.version}）。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '更新规则失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  if (loading) {
    return (
      <SectionCard title="差评处理工作区">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  const selected = detail?.negativeReview ?? null;
  const contactDone = (detail?.actions ?? []).some(
    (a) => a.actionType === 'CONTACT_BUYER' && a.outcome === 'SUCCEEDED',
  );
  const gateOpen = selected ? !selected.contactBuyerRequired || contactDone : false;

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

      {/* ---------------------------------------------------------- 能力边界 */}
      <SectionCard title="能力边界">
        <ul className="text-copy-13 space-y-2">
          {(capabilities?.dimensions ?? []).map((dimension) => (
            <li key={dimension.dimension} className="flex flex-wrap items-start gap-2">
              <Badge tone={dimension.available ? 'success' : 'warning'}>
                {dimension.available ? '可用' : '未接入'}
              </Badge>
              <span className="font-medium">{dimension.description}</span>
              {!dimension.available && (
                <span className="text-muted-foreground">
                  （归属模块 {dimension.ownerModule}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground text-copy-12 mt-3">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 解决率统计 */}
      <SectionCard title="解决率统计（服务端算 · 前端只展示）">
        {!stats ? (
          <p className="text-muted-foreground text-copy-13">暂无统计数据。</p>
        ) : (
          <div className="text-copy-13 space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-muted-foreground">作用域</span>
              <select
                className={control}
                value={statsDays}
                onChange={(e) => setStatsDays(Number(e.target.value))}
              >
                <option value={7}>近 7 天</option>
                <option value={30}>近 30 天</option>
                <option value={90}>近 90 天</option>
                <option value={365}>近 365 天</option>
              </select>
              <span className="text-muted-foreground">
                {stats.scopeFrom} 起 · 处理单 <strong>{stats.totalCases}</strong> 单 · 已解决{' '}
                <strong>{stats.resolvedCases}</strong> 单 · 解决率{' '}
                <strong>{resolutionRateText(stats.resolutionRate)}</strong>
              </span>
            </div>
            <p className="text-muted-foreground text-copy-12">
              {stats.resolutionRateBasis}；{stats.resolutionRateNumerator}。
            </p>
            <div className="text-copy-12 flex flex-wrap gap-3">
              <span>
                待处理 <strong>{stats.openCases}</strong>
              </span>
              <span>
                处理中 <strong>{stats.inProgressCases}</strong>
              </span>
              <span>
                已关闭 <strong>{stats.closedCases}</strong>
              </span>
              <span>
                可申诉 <strong>{stats.appealEligibleCases}</strong>
              </span>
              <span>
                不可申诉 <strong>{stats.appealIneligibleCases}</strong>
              </span>
              <span>
                已建申诉 <strong>{stats.appealedCases}</strong>
              </span>
              <span>
                已联系买家 <strong>{stats.contactBuyerDoneCases}</strong>
              </span>
            </div>

            <div>
              <h3 className="font-medium">按原因分类</h3>
              {stats.byCause.length === 0 ? (
                <p className="text-muted-foreground text-copy-12 mt-1">
                  当前作用域内还没有处理单。
                </p>
              ) : (
                <ul className="text-copy-12 mt-1 space-y-1">
                  {stats.byCause.map((bucket) => (
                    <li key={bucket.causeCode ?? 'UNCLASSIFIED'}>
                      {causeLabel(bucket.causeCode ?? '')}：<strong>{bucket.count}</strong> 单 ·
                      已解决 <strong>{bucket.resolvedCount}</strong> · 可申诉{' '}
                      <strong>{bucket.appealEligibleCount}</strong>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h3 className="font-medium">按处理状态</h3>
              <ul className="text-copy-12 mt-1 flex flex-wrap gap-3">
                {stats.byStatus.map((bucket) => (
                  <li key={bucket.status}>
                    {caseStatusLabel(bucket.status)}：<strong>{bucket.count}</strong>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="font-medium">按申诉状态</h3>
              {stats.byAppealStatus.length === 0 ? (
                <p className="text-muted-foreground text-copy-12 mt-1">
                  当前作用域内没有申诉任务。
                </p>
              ) : (
                <ul className="text-copy-12 mt-1 flex flex-wrap gap-3">
                  {stats.byAppealStatus.map((bucket) => (
                    <li key={bucket.status}>
                      {appealStatusLabel(bucket.status)}：<strong>{bucket.count}</strong>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h3 className="flex items-center gap-1 font-medium">
                <ShieldCheck className="h-4 w-4" />
                申诉配额（当前周期）
              </h3>
              {stats.byPlatformQuota.length === 0 ? (
                <p className="text-muted-foreground text-copy-12 mt-1">
                  还没有配置任何平台策略 —— 没有策略就没有「差评」这个口径。
                </p>
              ) : (
                <ul className="text-copy-12 mt-1 space-y-1">
                  {stats.byPlatformQuota.map((bucket) => (
                    <li key={bucket.platformCode}>
                      {bucket.platformCode}：{bucket.quotaPeriod}（{bucket.quotaPeriodKey}）· 配额{' '}
                      <strong>{bucket.quotaLimit}</strong> · 已用{' '}
                      <strong>{bucket.quotaUsed}</strong> · 剩余{' '}
                      <strong>{bucket.quotaRemaining ?? '—'}</strong>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <p className="text-muted-foreground text-copy-12">{stats.note}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 处理规则 */}
      <SectionCard
        title="处理规则（原因分类与申诉判定真的可配）"
        description="差评阈值、平台是否支持申诉、申诉配额都在规则里；没有平台策略就建不了处理单。"
      >
        <div className="text-copy-13 space-y-4">
          {!rules || rules.items.length === 0 ? (
            <EmptyState
              title="还没有配置差评处理规则"
              description="先为店铺所在的平台建一条「平台策略」（差评阈值 + 是否支持申诉 + 申诉配额），再按原因建「原因规则」。"
            />
          ) : (
            <ul className="space-y-2">
              {rules.items.map((rule) => (
                <li key={rule.id} className="flex flex-wrap items-center gap-2">
                  <Badge tone={rule.ruleScope === 'PLATFORM' ? 'primary' : 'info'}>
                    {ruleScopeLabel(rule.ruleScope)}
                  </Badge>
                  <span className="font-medium">{rule.label}</span>
                  <span className="text-muted-foreground">
                    {rule.platformCode}
                    {rule.causeCode ? ` · ${causeLabel(rule.causeCode)}` : ''} · 阈值{' '}
                    {rule.negativeRatingThreshold} ·{' '}
                    {rule.appealSupported
                      ? `支持申诉（配额 ${rule.appealQuotaLimit}/${rule.quotaPeriod}）`
                      : '**不支持申诉**'}
                    {rule.ruleScope === 'CAUSE' &&
                      (rule.appealEligible ? ' · 该原因可申诉' : ' · 该原因不可申诉')}
                  </span>
                  <span className="text-muted-foreground">v{rule.version}</span>
                  {rule.ruleScope === 'PLATFORM' && writable && (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => void bumpQuota(rule)}
                    >
                      配额 +1
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {writable && (
            <div className="border-border grid gap-3 rounded-md border p-3 md:grid-cols-3">
              <Field label="作用域">
                <select
                  className={control}
                  value={ruleScope}
                  onChange={(e) => setRuleScope(e.target.value)}
                >
                  <option value="PLATFORM">平台策略（逐平台）</option>
                  <option value="CAUSE">原因规则（逐平台 × 逐原因）</option>
                </select>
              </Field>
              <Field label="平台">
                <select
                  className={control}
                  value={rulePlatform}
                  onChange={(e) => setRulePlatform(e.target.value)}
                >
                  <option value="">请选择平台</option>
                  {[...new Set(shops.map((s) => s.platformCode))].map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </select>
              </Field>
              {ruleScope === 'CAUSE' ? (
                <Field label="原因分类">
                  <select
                    className={control}
                    value={ruleCause}
                    onChange={(e) => setRuleCause(e.target.value)}
                  >
                    {causeCodes.map((code) => (
                      <option key={code} value={code}>
                        {causeLabel(code)}
                      </option>
                    ))}
                  </select>
                </Field>
              ) : (
                <Field label="差评阈值（rating ≤ 该值即差评）">
                  <input
                    className={control}
                    type="number"
                    min={1}
                    max={5}
                    value={ruleThreshold}
                    onChange={(e) => setRuleThreshold(e.target.value)}
                  />
                </Field>
              )}
              {ruleScope === 'PLATFORM' && (
                <>
                  <Field label="平台支持申诉">
                    <select
                      className={control}
                      value={ruleAppealSupported ? 'true' : 'false'}
                      onChange={(e) => setRuleAppealSupported(e.target.value === 'true')}
                    >
                      <option value="true">支持</option>
                      <option value="false">不支持</option>
                    </select>
                  </Field>
                  <Field label="申诉配额（每周期）">
                    <input
                      className={control}
                      type="number"
                      min={0}
                      value={ruleQuota}
                      onChange={(e) => setRuleQuota(e.target.value)}
                    />
                  </Field>
                  <Field label="配额周期">
                    <select
                      className={control}
                      value={rulePeriod}
                      onChange={(e) => setRulePeriod(e.target.value)}
                    >
                      <option value="MONTHLY">月度</option>
                      <option value="QUARTERLY">季度</option>
                      <option value="YEARLY">年度</option>
                      <option value="NONE">不开放</option>
                    </select>
                  </Field>
                </>
              )}
              {ruleScope === 'CAUSE' && (
                <>
                  <Field label="该原因可申诉">
                    <select
                      className={control}
                      value={ruleEligible ? 'true' : 'false'}
                      onChange={(e) => setRuleEligible(e.target.value === 'true')}
                    >
                      <option value="true">可申诉</option>
                      <option value="false">不可申诉</option>
                    </select>
                  </Field>
                  <Field label="判定依据模板">
                    <input
                      className={control}
                      value={ruleBasis}
                      onChange={(e) => setRuleBasis(e.target.value)}
                      placeholder="例如：物流事实可查且在平台申诉时限内"
                    />
                  </Field>
                </>
              )}
              <Field label="规则名称">
                <input
                  className={control}
                  value={ruleLabel}
                  onChange={(e) => setRuleLabel(e.target.value)}
                  placeholder="留空则自动命名"
                />
              </Field>
              <div className="md:col-span-3">
                <Button disabled={busy || !rulePlatform} onClick={() => void submitRule()}>
                  新建规则
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 待接手的差评 */}
      <SectionCard
        title="待接手的差评（还没建处理单）"
        description="差评来自 15.2.1.1 的评价列表；只有低于该平台阈值的评价才会出现在这里。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <input
              className={control}
              placeholder="星级"
              value={candidateRating}
              onChange={(e) => setCandidateRating(e.target.value)}
            />
            <input
              className={control}
              placeholder="关键字"
              value={candidateKeyword}
              onChange={(e) => setCandidateKeyword(e.target.value)}
            />
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      >
        {!candidates || candidates.items.length === 0 ? (
          <EmptyState
            title="没有待接手的差评"
            description="当前筛选条件下没有低于平台阈值的评价（或都已经被建过处理单了）。"
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {candidates.items.map((row) => (
              <li key={row.reviewId} className="flex flex-wrap items-center gap-2">
                <Badge tone={row.rating <= 2 ? 'danger' : 'neutral'}>{row.rating} 星</Badge>
                <span className="font-medium">{row.reviewNo}</span>
                <span className="text-muted-foreground">
                  {row.shopName} · {row.platformCode} · {row.content ?? '（无正文）'}
                </span>
                {row.caseExists ? (
                  <Badge tone="info">已有处理单 {row.caseNo}</Badge>
                ) : writable ? (
                  <Button
                    size="sm"
                    disabled={busy || !row.policyConfigured}
                    onClick={() => void createCase(row.reviewId)}
                  >
                    建处理单
                  </Button>
                ) : null}
                {!row.policyConfigured && (
                  <span className="text-muted-foreground">
                    （该平台还没有策略 —— 先在上面配一条平台策略）
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 处理单清单 */}
      <SectionCard title="处理单清单">
        <div className="text-copy-13 space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="店铺">
              <select
                className={control}
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
              >
                <option value="">全部店铺</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="平台">
              <select
                className={control}
                value={platformCode}
                onChange={(e) => setPlatformCode(e.target.value)}
              >
                <option value="">全部平台</option>
                {[...new Set(shops.map((s) => s.platformCode))].map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="原因分类">
              <select
                className={control}
                value={causeCode}
                onChange={(e) => setCauseCode(e.target.value)}
              >
                <option value="">全部原因</option>
                {causeCodes.map((code) => (
                  <option key={code} value={code}>
                    {causeLabel(code)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="处理状态">
              <select
                className={control}
                value={caseStatus}
                onChange={(e) => setCaseStatus(e.target.value)}
              >
                <option value="">全部状态</option>
                {caseStatuses.map((status) => (
                  <option key={status} value={status}>
                    {caseStatusLabel(status)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="解决状态">
              <select
                className={control}
                value={resolutionStatus}
                onChange={(e) => setResolutionStatus(e.target.value)}
              >
                <option value="">全部</option>
                {resolutionStatuses.map((status) => (
                  <option key={status} value={status}>
                    {resolutionStatusLabel(status)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="是否可申诉">
              <select
                className={control}
                value={appealEligible}
                onChange={(e) => setAppealEligible(e.target.value)}
              >
                <option value="">全部</option>
                <option value="true">可申诉</option>
                <option value="false">不可申诉</option>
              </select>
            </Field>
            <Field label="星级">
              <input
                className={control}
                value={rating}
                onChange={(e) => setRating(e.target.value)}
                placeholder="1-5"
              />
            </Field>
            <Field label="关键字">
              <input
                className={control}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="处理单号 / 评价号 / 正文"
              />
            </Field>
          </div>

          {!cases || cases.items.length === 0 ? (
            <EmptyState
              title={hasFilter ? '当前筛选下没有处理单' : '还没有差评处理单'}
              description={
                hasFilter
                  ? '换一个筛选条件试试 —— 解决率的分母也会跟着换口径。'
                  : '先在上面「待接手的差评」里挑一条建处理单。'
              }
            />
          ) : (
            <ul className="space-y-2">
              {cases.items.map((row) => (
                <li key={row.id} className="flex flex-wrap items-center gap-2">
                  <Badge tone={row.caseStatus === 'RESOLVED' ? 'success' : 'neutral'}>
                    {caseStatusLabel(row.caseStatus)}
                  </Badge>
                  <span className="font-medium">{row.caseNo}</span>
                  <span className="text-muted-foreground">
                    {row.shopName} · {row.platformCode} · {row.reviewRating} 星 · 原因{' '}
                    {causeLabel(row.causeCode ?? '')} ·{' '}
                    {resolutionStatusLabel(row.resolutionStatus)}
                    {row.appealEligible ? ' · 可申诉' : ' · 不可申诉'}
                  </span>
                  <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                    详情
                  </Button>
                </li>
              ))}
            </ul>
          )}

          {cases && cases.totalPages > 1 && (
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={pageIndex <= 0}
                onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground">
                第 {pageIndex + 1} / {cases.totalPages} 页 · 共 {cases.totalElements} 单
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={pageIndex + 1 >= cases.totalPages}
                onClick={() => setPageIndex((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 处理单详情 */}
      {selected && detail && (
        <SectionCard
          title={`处理单 ${selected.caseNo}`}
          description={`差评 ${selected.reviewNo}（${selected.reviewRating} 星）· ${selected.shopName} · ${selected.platformCode}`}
        >
          <div className="text-copy-13 space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={selected.caseStatus === 'RESOLVED' ? 'success' : 'neutral'}>
                {caseStatusLabel(selected.caseStatus)}
              </Badge>
              <Badge tone={selected.resolutionStatus === 'RESOLVED' ? 'success' : 'warning'}>
                {resolutionStatusLabel(selected.resolutionStatus)}
              </Badge>
              <Badge tone={selected.appealEligible ? 'info' : 'neutral'}>
                {selected.appealEligible ? '可申诉' : '不可申诉'}
              </Badge>
              <span className="text-muted-foreground">
                差评阈值 rating ≤ {selected.negativeRatingThreshold} · 平台{' '}
                {selected.appealSupported ? '支持' : '不支持'}申诉 · 配额{' '}
                {selected.appealQuotaLimit}/{selected.quotaPeriod}
              </span>
            </div>

            {selected.reviewContent && (
              <blockquote className="border-border text-muted-foreground text-copy-12 border-l-2 pl-3">
                {selected.reviewContent}
              </blockquote>
            )}

            {/* ---- 原因分类与判定依据 ---- */}
            <div className="border-border space-y-2 rounded-md border p-3">
              <h3 className="flex items-center gap-1 font-medium">
                <Gavel className="h-4 w-4" />
                原因分类与申诉判定
              </h3>
              {selected.causeCode ? (
                <>
                  <p>
                    原因：<strong>{causeLabel(selected.causeCode)}</strong>
                    {selected.causeNote ? ` —— ${selected.causeNote}` : ''}
                  </p>
                  <p className="text-muted-foreground text-copy-12">
                    判定依据：{selected.appealBasis ?? '（无）'}
                  </p>
                </>
              ) : (
                <p className="text-muted-foreground">还没有标记原因分类。</p>
              )}
              {writable && (
                <div className="flex flex-wrap items-end gap-2">
                  <Field label="原因分类">
                    <select
                      className={control}
                      value={classifyCause}
                      onChange={(e) => setClassifyCause(e.target.value)}
                    >
                      {causeCodes.map((code) => (
                        <option key={code} value={code}>
                          {causeLabel(code)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="判定依据 / 证据">
                    <input
                      className={control}
                      value={classifyNote}
                      onChange={(e) => setClassifyNote(e.target.value)}
                      placeholder="买家说了什么 / 物流事实是什么"
                    />
                  </Field>
                  <Button disabled={busy} onClick={() => void submitClassify()}>
                    标记原因
                  </Button>
                </div>
              )}
            </div>

            {/* ---- 动作流水 ---- */}
            <div className="border-border space-y-2 rounded-md border p-3">
              <h3 className="font-medium">动作流水（事实，写进去就不改写）</h3>
              {detail.actions.length === 0 ? (
                <p className="text-muted-foreground">还没有任何动作。</p>
              ) : (
                <ul className="text-copy-12 space-y-1">
                  {detail.actions.map((a: CrmNegativeReviewActionRow) => (
                    <li key={a.id} className="flex flex-wrap items-center gap-2">
                      <Badge tone={a.outcome === 'SUCCEEDED' ? 'success' : 'warning'}>
                        {outcomeLabel(a.outcome)}
                      </Badge>
                      <span className="font-medium">{actionTypeLabel(a.actionType)}</span>
                      <span className="text-muted-foreground">
                        {transportLabel(a.transport)} · {a.actionAt}
                      </span>
                      {a.summary && <span>{a.summary}</span>}
                      {a.afterSalesReason && (
                        <span className="text-muted-foreground">
                          转交原因：{a.afterSalesReason}（11 售后未交付，只落转交意图）
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}

              {writable && (
                <div className="flex flex-wrap items-end gap-2">
                  <Field label="动作">
                    <select
                      className={control}
                      value={actionType}
                      onChange={(e) => setActionType(e.target.value)}
                    >
                      {actionTypes.map((type) => (
                        <option key={type} value={type}>
                          {actionTypeLabel(type)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="触达方式">
                    <select
                      className={control}
                      value={actionTransport}
                      onChange={(e) => setActionTransport(e.target.value)}
                    >
                      <option value="MANUAL_HANDOFF">人工交接</option>
                      <option value="PLATFORM_API">平台接口（未接入）</option>
                      <option value="INTERNAL">内部动作</option>
                    </select>
                  </Field>
                  <Field label="结果">
                    <select
                      className={control}
                      value={actionOutcome}
                      onChange={(e) => setActionOutcome(e.target.value)}
                    >
                      <option value="SUCCEEDED">成功</option>
                      <option value="FAILED">失败</option>
                      <option value="PENDING">进行中</option>
                    </select>
                  </Field>
                  <Field label="说明">
                    <input
                      className={control}
                      value={actionSummary}
                      onChange={(e) => setActionSummary(e.target.value)}
                      placeholder="这一步做了什么"
                    />
                  </Field>
                  {actionType === 'AFTER_SALES_HANDOFF' && (
                    <Field label="转交原因（必填）">
                      <input
                        className={control}
                        value={afterSalesReason}
                        onChange={(e) => setAfterSalesReason(e.target.value)}
                      />
                    </Field>
                  )}
                  <Field label="发生时刻（可回填）">
                    <input
                      className={control}
                      value={actionOccurredAt}
                      onChange={(e) => setActionOccurredAt(e.target.value)}
                      placeholder="2026-10-10T08:00:00+08:00"
                    />
                  </Field>
                  <Button disabled={busy} onClick={() => void submitAction()}>
                    记录动作
                  </Button>
                </div>
              )}
            </div>

            {/* ---- 申诉任务 ---- */}
            <div className="border-border space-y-2 rounded-md border p-3">
              <h3 className="flex items-center gap-1 font-medium">
                <AlertTriangle className="h-4 w-4" />
                申诉任务
              </h3>
              {!gateOpen && (
                <p className="text-warning text-copy-12">
                  🔴 还没走完「联系买家」这一步 —— 按蓝图的正确路径，必须**先联系买家解决问题**，
                  才能请求修改 / 提交申诉（直接申诉容易被驳回且消耗申诉配额）。
                </p>
              )}
              {detail.appeals.length === 0 ? (
                <p className="text-muted-foreground">还没有申诉任务。</p>
              ) : (
                <ul className="text-copy-12 space-y-2">
                  {detail.appeals.map((appeal: CrmNegativeReviewAppealRow) => (
                    <li key={appeal.id} className="flex flex-wrap items-center gap-2">
                      <Badge
                        tone={
                          appeal.appealStatus === 'APPROVED'
                            ? 'success'
                            : appeal.appealStatus === 'REJECTED'
                              ? 'danger'
                              : 'neutral'
                        }
                      >
                        {appealStatusLabel(appeal.appealStatus)}
                      </Badge>
                      <span className="font-medium">{appeal.appealNo}</span>
                      <span className="text-muted-foreground">
                        {appealTypeLabel(appeal.appealType)} · {causeLabel(appeal.causeCode)} · 配额{' '}
                        {appeal.quotaIndex}/{appeal.quotaLimit}（{appeal.quotaPeriodKey}）
                      </span>
                      <span>{appeal.reason}</span>
                      {appeal.appealStatus === 'DRAFT' && writable && (
                        <>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => void submitToPlatform(appeal)}
                          >
                            提交到平台（未接入）
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => void cancelAppeal(appeal)}
                          >
                            撤回
                          </Button>
                        </>
                      )}
                      {appeal.appealStatus === 'DRAFT' && writable && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy || !appealSubmittedAt}
                          onClick={() => void markSubmitted(appeal)}
                        >
                          登记平台侧已提交
                        </Button>
                      )}
                      {appeal.appealStatus === 'SUBMITTED' && writable && (
                        <>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy || !appealSubmittedAt}
                            onClick={() => void recordOutcome(appeal, 'APPROVED')}
                          >
                            登记通过
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy || !appealSubmittedAt}
                            onClick={() => void recordOutcome(appeal, 'REJECTED')}
                          >
                            登记驳回
                          </Button>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}

              {writable && (
                <div className="flex flex-wrap items-end gap-2">
                  <Field label="申诉类型">
                    <select
                      className={control}
                      value={appealType}
                      onChange={(e) => setAppealType(e.target.value)}
                    >
                      {appealTypes.map((type) => (
                        <option key={type} value={type}>
                          {appealTypeLabel(type)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="申诉理由">
                    <input
                      className={control}
                      value={appealReason}
                      onChange={(e) => setAppealReason(e.target.value)}
                      placeholder="为什么这条差评应当被移除或修改"
                    />
                  </Field>
                  <Field label="平台侧时刻 / 判定时刻">
                    <input
                      className={control}
                      value={appealSubmittedAt}
                      onChange={(e) => setAppealSubmittedAt(e.target.value)}
                      placeholder="2026-10-10T08:00:00+08:00"
                    />
                  </Field>
                  {appealSubmittedAt === '' && (
                    <Field label="平台侧单号">
                      <input
                        className={control}
                        value={appealReference}
                        onChange={(e) => setAppealReference(e.target.value)}
                      />
                    </Field>
                  )}
                  <Button
                    disabled={busy || !gateOpen || !appealReason}
                    onClick={() => void submitAppeal()}
                  >
                    建申诉任务
                  </Button>
                </div>
              )}
            </div>

            {/* ---- 关联订单上下文 ---- */}
            <div className="border-border space-y-2 rounded-md border p-3">
              <h3 className="font-medium">关联订单上下文</h3>
              {detail.orderContext.available ? (
                <p className="text-copy-12">
                  订单 {detail.orderContext.orderNo ?? '—'} · 状态{' '}
                  {detail.orderContext.orderStatus ?? '—'} · 签收{' '}
                  {detail.orderContext.deliveredAt ?? '—'} · 承运商{' '}
                  {detail.orderContext.carrierCode ?? '—'}
                </p>
              ) : (
                <p className="text-muted-foreground text-copy-12">
                  订单上下文不可用：{detail.orderContext.reason}
                </p>
              )}
            </div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

/** 把后端抛出的原因取出来。 */
function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
