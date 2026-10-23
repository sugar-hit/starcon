'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { CheckCircle2, Globe, Layers, MessageSquare, RefreshCw, Send, XCircle } from 'lucide-react';
import type {
  CrmReviewReplyBatchResult,
  CrmReviewReplyCapabilities,
  CrmReviewReplyDetail,
  CrmReviewReplyPage,
  CrmReviewReplyPolicyPage,
  CrmReviewReplyPolicyRow,
  CrmReviewReplyRow,
  CrmReviewReplyTemplateDetail,
  CrmReviewReplyTemplatePage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  archiveReviewReplyTemplate,
  batchSummaryLabel,
  cancelReviewReply,
  createReviewReply,
  createReviewReplyBatch,
  createReviewReplyTemplate,
  deliverReviewReply,
  deliveryOutcomeLabel,
  fetchReviewReplyBatches,
  fetchReviewReplyCapabilities,
  fetchReviewReplyPolicies,
  fetchReviewReply,
  fetchReviewReplyTemplate,
  fetchReviewReplies,
  fetchReviewReplyTemplates,
  publishReviewReplyTemplate,
  replyStatusLabel,
  replyTypeLabel,
  reviewScopeLabel,
  saveReviewReplyPolicy,
  templateStatusLabel,
  templateVersionLabel,
} from './review-reply-model';
import { fetchProductReviews } from './product-review-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.2.1.2 评价回复工作区。
 *
 * <p>页面按「先看清边界、再管话术、后回评价」的顺序排，因为蓝图加粗句是
 * 「<b>差评回复是转化机会也是合规风险，模板必须符合平台措辞规范</b>」——
 * 界面上第一件要让人看到的是**能力边界**与**措辞规范**，
 * 而不是一只可以随手点「回复」的按钮。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台投递、差评处理、买家关怀三维是 available=false，
 *       页面把它们<b>显式灰掉</b>并写清归属模块。</li>
 *   <li><b>措辞规范</b> —— 逐平台 × 逐形态，**带版本与生效时间**。
 *       没配规范的平台**写不出回复**（后端 409），页面把这句话写在明处。</li>
 *   <li><b>回复模板</b> —— 一个模板 = 一个版本；改内容要发新版本。
 *       多语言条目逐条列出来。</li>
 *   <li><b>写回复</b> —— 目标评价从上游 15.2.1.1 的真实评价列表里选，
 *       正文可套模板（多语言自动匹配）也可手写；
 *       🔴 <b>不合规的正文发不出去</b>，错误原因原样显示。</li>
 *   <li><b>回复记录</b> —— 状态标签必须写清「<b>待投递 = 还没发到平台</b>」，
 *       并显示当时用的模板版本与规范版本。</li>
 *   <li><b>批量回复</b> —— 同类评价逐条落库，<b>跳过的每一条都带原因</b>。</li>
 * </ul>
 */
export function ReviewReplyWorkspace() {
  const { session } = useIdentity();
  const canRead = session.permissions.includes('crm.review_reply.read');
  const writable = session.permissions.includes('crm.review_reply.update');

  const [capabilities, setCapabilities] = useState<CrmReviewReplyCapabilities | null>(null);
  const [policies, setPolicies] = useState<CrmReviewReplyPolicyPage | null>(null);
  const [templates, setTemplates] = useState<CrmReviewReplyTemplatePage | null>(null);
  const [replies, setReplies] = useState<CrmReviewReplyPage | null>(null);
  const [batches, setBatches] = useState<CrmReviewReplyBatchResult | null>(null);
  const [detail, setDetail] = useState<CrmReviewReplyDetail | null>(null);
  const [templateDetail, setTemplateDetail] = useState<CrmReviewReplyTemplateDetail | null>(null);

  // 目标评价候选（上游 15.2.1.1 的真实评价列表）
  const [candidates, setCandidates] = useState<
    {
      id: string;
      reviewNo: string;
      rating: number;
      content: string | null;
      platformCode: string;
      shopName: string;
    }[]
  >([]);
  const [candidateError, setCandidateError] = useState<string | null>(null);

  // 筛选
  const [platformCode, setPlatformCode] = useState('');
  const [reviewScope, setReviewScope] = useState('');
  const [replyType, setReplyType] = useState('PUBLIC_REPLY');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 写回复
  const [targetReviewId, setTargetReviewId] = useState('');
  const [replyLocale, setReplyLocale] = useState('en-US');
  const [templateId, setTemplateId] = useState('');
  const [replyBody, setReplyBody] = useState('');

  // 措辞规范
  const [policyPlatform, setPolicyPlatform] = useState('AMAZON');
  const [policyReplyType, setPolicyReplyType] = useState('PUBLIC_REPLY');
  const [policyMaxLength, setPolicyMaxLength] = useState('500');
  const [policyMinLength, setPolicyMinLength] = useState('10');
  const [policyEffectiveFrom, setPolicyEffectiveFrom] = useState('');

  // 模板
  const [templateKey, setTemplateKey] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [templateScope, setTemplateScope] = useState('ALL');
  const [templateLocale, setTemplateLocale] = useState('en-US');
  const [templateBody, setTemplateBody] = useState('');

  // 批量
  const [batchPlatform, setBatchPlatform] = useState('AMAZON');
  const [batchReplyType, setBatchReplyType] = useState('PUBLIC_REPLY');
  const [batchScope, setBatchScope] = useState('POSITIVE');
  const [batchLocale, setBatchLocale] = useState('en-US');
  const [batchTemplateId, setBatchTemplateId] = useState('');
  const [batchLimit, setBatchLimit] = useState('50');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const hasFilter = useMemo(
    () => Boolean(platformCode || reviewScope || replyType || status || keyword),
    [platformCode, reviewScope, replyType, status, keyword],
  );

  const load = useCallback(async () => {
    if (!canRead) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [caps, policyPage, templatePage, replyPage, batchPage] = await Promise.all([
        fetchReviewReplyCapabilities(),
        fetchReviewReplyPolicies({ size: 20 }),
        fetchReviewReplyTemplates({ size: 20 }),
        fetchReviewReplies({
          platformCode: platformCode || undefined,
          reviewScope: reviewScope || undefined,
          replyType: replyType || undefined,
          status: status || undefined,
          keyword: keyword || undefined,
          page: pageIndex,
          size: 20,
        }),
        fetchReviewReplyBatches({ size: 10 }),
      ]);
      setCapabilities(caps);
      setPolicies(policyPage);
      setTemplates(templatePage);
      setReplies(replyPage);
      setBatches(
        batchPage.items.length > 0
          ? {
              batch: batchPage.items[0]!,
              replies: [],
              skipped: [],
              note: '下面是最近一次批量回复的批次，点开可看它写了哪些回复、跳过了哪些。',
            }
          : null,
      );
    } catch (e) {
      setError(errorText(e, '评价回复工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [canRead, platformCode, reviewScope, replyType, status, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 目标评价候选：走**上游 15.2.1.1** 的评价列表（本模块只读引用）。
  const loadCandidates = useCallback(async () => {
    setCandidateError(null);
    try {
      const page = await fetchProductReviews({
        platformCode: platformCode || undefined,
        size: 20,
      });
      setCandidates(
        page.items.map((item) => ({
          id: item.id,
          reviewNo: item.reviewNo,
          rating: item.rating,
          content: item.content,
          platformCode: item.platformCode,
          shopName: item.shopName,
        })),
      );
    } catch (e) {
      setCandidateError(errorText(e, '读不到上游的评价列表（15.2.1.1）—— 没有它就没法挑目标评价'));
      setCandidates([]);
    }
  }, [platformCode]);

  useEffect(() => {
    void loadCandidates();
  }, [loadCandidates]);

  const savePolicy = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const saved = await saveReviewReplyPolicy({
        platformCode: policyPlatform,
        replyType: policyReplyType,
        minLength: Number(policyMinLength),
        maxLength: Number(policyMaxLength),
        allowContactInfo: false,
        allowPromotion: false,
        allowExternalLink: false,
        maxExclamation: 1,
        maxQuestionMarks: 2,
        requireApology: true,
        bannedTerms: [],
        effectiveFrom: policyEffectiveFrom,
        note: '',
      });
      setNotice(
        `已把 ${saved.platformCode} / ${replyTypeLabel(saved.replyType)} 的措辞规范更新为第 ${saved.version} 版。`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '配置措辞规范失败'));
    } finally {
      setBusy(false);
    }
  }, [
    policyPlatform,
    policyReplyType,
    policyMinLength,
    policyMaxLength,
    policyEffectiveFrom,
    load,
  ]);

  const submitTemplate = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createReviewReplyTemplate({
        templateKey,
        name: templateName || templateKey,
        platformCode: policyPlatform,
        replyType: policyReplyType,
        ratingScope: templateScope,
        effectiveFrom: policyEffectiveFrom,
        effectiveTo: '',
        variants: [{ locale: templateLocale, body: templateBody, isDefault: true }],
        note: '',
      });
      setNotice(
        `已创建模板草稿 ${created.template.templateKey} 第 ${created.template.version} 版（草稿可编辑，发布后才生效）。`,
      );
      setTemplateKey('');
      setTemplateName('');
      setTemplateBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '创建模板失败'));
    } finally {
      setBusy(false);
    }
  }, [
    templateKey,
    templateName,
    templateScope,
    templateLocale,
    templateBody,
    policyPlatform,
    policyReplyType,
    policyEffectiveFrom,
    load,
  ]);

  const publishTemplate = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const published = await publishReviewReplyTemplate(id);
        setNotice(
          `模板 ${published.template.templateKey} 第 ${published.template.version} 版已生效。`,
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '发布模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const archiveTemplate = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await archiveReviewReplyTemplate(id);
        setNotice('模板已下架。');
        await load();
      } catch (e) {
        setActionError(errorText(e, '下架模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const openTemplate = useCallback(async (id: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setTemplateDetail(await fetchReviewReplyTemplate(id));
    } catch (e) {
      setActionError(errorText(e, '读取模板详情失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitReply = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createReviewReply({
        reviewId: targetReviewId,
        replyType,
        locale: replyLocale,
        body: templateId ? '' : replyBody,
        templateId: templateId || null,
        note: '',
      });
      setNotice(
        `已写入回复 ${created.replyNo}（${replyTypeLabel(created.replyType)}）。🔴 注意：平台适配器未接入，这条回复**还没有发到平台**。`,
      );
      setReplyBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '写回复失败（不合规的正文会被直接拒绝）'));
    } finally {
      setBusy(false);
    }
  }, [targetReviewId, replyType, replyLocale, templateId, replyBody, load]);

  const runDeliver = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const result = await deliverReviewReply(id);
        setNotice(result.note);
        await load();
      } catch (e) {
        // 🔴 平台评价回复适配器未接入 ⇒ 这里恒为 409。页面**如实显示原因**，
        //   不给一个「点了就成功」的假按钮。
        setActionError(
          errorText(e, '平台投递失败（适配器未接入：这次尝试已留痕，但回复没有发到平台）'),
        );
        await load();
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const runCancel = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await cancelReviewReply(id, '运营撤回');
        setNotice('已撤回这条待投递回复（撤回后可以再回一次）。');
        await load();
      } catch (e) {
        setActionError(errorText(e, '撤回回复失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const openDetail = useCallback(async (id: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchReviewReply(id));
    } catch (e) {
      setActionError(errorText(e, '读取回复详情失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const runBatch = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await createReviewReplyBatch({
        platformCode: batchPlatform,
        shopId: null,
        replyType: batchReplyType,
        locale: batchLocale,
        ratingScope: batchScope,
        templateId: batchTemplateId,
        limit: Number(batchLimit),
        note: '',
      });
      setBatches(result);
      setNotice(batchSummaryLabel(result));
      await load();
    } catch (e) {
      setActionError(errorText(e, '批量回复失败'));
    } finally {
      setBusy(false);
    }
  }, [batchPlatform, batchReplyType, batchScope, batchLocale, batchTemplateId, batchLimit, load]);

  if (!canRead) {
    return (
      <SectionCard title="评价回复">
        <p className="text-muted-foreground text-copy-13">
          当前角色没有「crm.review_reply.read」权限，看不到评价回复工作区。
        </p>
      </SectionCard>
    );
  }

  if (loading) {
    return (
      <SectionCard title="评价回复工作区">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

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
        {capabilities?.boundaryNote && (
          <p className="text-muted-foreground text-copy-12 mt-3">{capabilities.boundaryNote}</p>
        )}
        {capabilities?.highlight && (
          <p className="text-muted-foreground text-copy-12 mt-1">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 措辞规范 */}
      <SectionCard title="平台措辞规范（逐平台 × 逐形态，带版本与生效时间）">
        {!policies || policies.items.length === 0 ? (
          <EmptyState
            title="还没有配置任何平台的措辞规范"
            description="规范没配的平台**写不出回复**（后端 409）—— 未配置既意味着没开通，也意味着没法校验合规。先在下面配一版。"
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {policies.items.map((policy: CrmReviewReplyPolicyRow) => (
              <li key={policy.id} className="flex flex-wrap items-center gap-2">
                <Badge tone={policy.status === 'ACTIVE' ? 'success' : 'neutral'}>
                  第 {policy.version} 版
                </Badge>
                <span className="font-medium">{policy.platformCode}</span>
                <span className="text-muted-foreground">
                  {replyTypeLabel(policy.replyType)} · 长度 {policy.minLength}-{policy.maxLength} ·
                  感叹号 ≤{policy.maxExclamation} · 问号 ≤{policy.maxQuestionMarks} ·
                  {policy.requireApology ? '差评必致歉' : '差评不强制致歉'} ·
                  {policy.allowContactInfo ? '允许留联系方式' : '禁止留联系方式'} ·
                  {policy.allowPromotion ? '允许促销措辞' : '禁止促销措辞'} ·
                  {policy.allowExternalLink ? '允许外链' : '禁止外链'}
                </span>
                <span className="text-muted-foreground">
                  生效 {policy.effectiveFrom} 起
                  {policy.effectiveTo ? ` 至 ${policy.effectiveTo}` : '（无终止）'}
                </span>
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="mt-4 space-y-3">
            <p className="text-muted-foreground text-copy-12">
              🔴 改规范 = <strong>发新版本</strong>（旧版本留在历史里）。生效区间不允许重叠 ——
              「当时生效的是哪一版」必须唯一。
            </p>
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="平台">
                <input
                  className={control}
                  value={policyPlatform}
                  onChange={(e) => setPolicyPlatform(e.target.value.toUpperCase())}
                  placeholder="AMAZON"
                />
              </Field>
              <Field label="回复形态">
                <select
                  className={control}
                  value={policyReplyType}
                  onChange={(e) => setPolicyReplyType(e.target.value)}
                >
                  <option value="PUBLIC_REPLY">公开回复</option>
                  <option value="SELLER_COMMENT_APPEND">卖家评论追加</option>
                </select>
              </Field>
              <Field label="新版本生效起点" hint="带时区偏移量，例如 2026-10-01T00:00:00+08:00">
                <input
                  className={control}
                  value={policyEffectiveFrom}
                  onChange={(e) => setPolicyEffectiveFrom(e.target.value)}
                />
              </Field>
              <Field label="正文长度下限">
                <input
                  className={control}
                  value={policyMinLength}
                  onChange={(e) => setPolicyMinLength(e.target.value)}
                />
              </Field>
              <Field label="正文长度上限">
                <input
                  className={control}
                  value={policyMaxLength}
                  onChange={(e) => setPolicyMaxLength(e.target.value)}
                />
              </Field>
            </div>
            <Button onClick={() => void savePolicy()} size="sm" variant="secondary" disabled={busy}>
              <RefreshCw className="mr-1 h-4 w-4" />
              发布新版本规范
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 回复模板 */}
      <SectionCard title="回复模板（一行 = 一个版本；改内容要发新版本）">
        {!templates || templates.items.length === 0 ? (
          <EmptyState
            title="还没有回复模板"
            description="模板是「回复评价」这件事的**可复用资产**：一个模板 = 一个版本，带生效时间；改内容要发新版本，旧版本留在历史里。"
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {templates.items.map((template) => (
              <li key={template.id} className="flex flex-wrap items-center gap-2">
                <Badge tone={template.status === 'ACTIVE' ? 'success' : 'neutral'}>
                  {templateStatusLabel(template.status)}
                </Badge>
                <span className="font-medium">
                  {template.templateKey} 第 {template.version} 版
                </span>
                <span className="text-muted-foreground">
                  {template.platformCode} · {replyTypeLabel(template.replyType)} ·{' '}
                  {reviewScopeLabel(template.ratingScope)} · 生效 {template.effectiveFrom} 起
                </span>
                <Button size="sm" variant="ghost" onClick={() => void openTemplate(template.id)}>
                  查看多语言
                </Button>
                {writable && template.status === 'DRAFT' && (
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void publishTemplate(template.id)}
                  >
                    发布
                  </Button>
                )}
                {writable && template.status === 'ACTIVE' && (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy}
                    onClick={() => void archiveTemplate(template.id)}
                  >
                    下架
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}

        {templateDetail && (
          <div className="text-copy-13 mt-3 space-y-2">
            <p className="font-medium">
              {templateDetail.template.templateKey} 第 {templateDetail.template.version} 版 ·{' '}
              {templateDetail.variants.length} 种语言
            </p>
            <ul className="text-copy-12 space-y-1">
              {templateDetail.variants.map((variant) => (
                <li key={variant.id}>
                  <Globe className="mr-1 inline h-3 w-3" />
                  <span className="font-medium">{variant.locale}</span>
                  {variant.isDefault && <span className="text-muted-foreground">（兜底语言）</span>}
                  <span className="text-muted-foreground">：{variant.body}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {writable && (
          <div className="mt-4 space-y-3">
            <p className="text-muted-foreground text-copy-12">
              🔴 新建的是**草稿**（可编辑）；发布时才强制校验措辞规范 —— 平台政策会变，
              所以内容一旦生效就**冻结**，要改就发新版本。
            </p>
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="模板键" hint="同一模板的各版本共用；改内容时用同一个键发新版本。">
                <input
                  className={control}
                  value={templateKey}
                  onChange={(e) => setTemplateKey(e.target.value)}
                  placeholder="NEGATIVE_APOLOGY"
                />
              </Field>
              <Field label="模板名称">
                <input
                  className={control}
                  value={templateName}
                  onChange={(e) => setTemplateName(e.target.value)}
                  placeholder="差评致歉"
                />
              </Field>
              <Field label="适用档位">
                <select
                  className={control}
                  value={templateScope}
                  onChange={(e) => setTemplateScope(e.target.value)}
                >
                  <option value="ALL">不限档位</option>
                  <option value="POSITIVE">好评（4-5 星）</option>
                  <option value="NEUTRAL">中评（3 星）</option>
                  <option value="NEGATIVE">差评（1-2 星）</option>
                </select>
              </Field>
              <Field label="语言" hint="BCP-47，例如 en-US / zh-CN。">
                <input
                  className={control}
                  value={templateLocale}
                  onChange={(e) => setTemplateLocale(e.target.value)}
                />
              </Field>
              <Field label="生效起点" hint="带时区偏移量。">
                <input
                  className={control}
                  value={policyEffectiveFrom}
                  onChange={(e) => setPolicyEffectiveFrom(e.target.value)}
                />
              </Field>
              <Field
                label="正文"
                hint="不合规的正文存不进来（促销 / 导流 / 质疑买家 / 差评不致歉）。"
              >
                <textarea
                  className={`${control} min-h-24`}
                  value={templateBody}
                  onChange={(e) => setTemplateBody(e.target.value)}
                />
              </Field>
            </div>
            <Button
              onClick={() => void submitTemplate()}
              size="sm"
              variant="secondary"
              disabled={busy}
            >
              <Layers className="mr-1 h-4 w-4" />
              新建模板草稿
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 写回复 */}
      <SectionCard title="写一条回复">
        <p className="text-muted-foreground text-copy-12 mb-3">
          目标评价来自上游 <strong>15.2.1.1 评价列表</strong>（本模块只读引用）。
          正文可套模板（按语言自动匹配；找不到就用模板的兜底语言），也可手写 ——
          <strong>两种都要过措辞校验</strong>。
        </p>
        {candidateError && (
          <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 mb-3 rounded-md px-3 py-2">
            {candidateError}
          </div>
        )}
        {candidates.length === 0 && !candidateError ? (
          <EmptyState
            title="没有可选的目标评价"
            description="上游还没有评价（或当前筛选下没有）。评价是回复的对象 —— 没有它就没有可回的东西。"
          />
        ) : (
          <ul className="text-copy-13 mb-3 max-h-56 space-y-1 overflow-y-auto">
            {candidates.map((candidate) => (
              <li key={candidate.id} className="flex flex-wrap items-center gap-2">
                <span className="text-muted-foreground">{candidate.reviewNo}</span>
                <span>
                  {candidate.rating} 星 · {candidate.shopName} · {candidate.platformCode}
                </span>
                <span className="text-muted-foreground">{candidate.content ?? '（无正文）'}</span>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setTargetReviewId(candidate.id)}
                  disabled={!writable}
                >
                  选它
                </Button>
              </li>
            ))}
          </ul>
        )}

        {writable && (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="目标评价 id" hint={targetReviewId || '先在上面选一条评价'}>
                <input
                  className={control}
                  value={targetReviewId}
                  onChange={(e) => setTargetReviewId(e.target.value)}
                />
              </Field>
              <Field label="回复形态">
                <select
                  className={control}
                  value={replyType}
                  onChange={(e) => setReplyType(e.target.value)}
                >
                  <option value="PUBLIC_REPLY">公开回复</option>
                  <option value="SELLER_COMMENT_APPEND">卖家评论追加</option>
                </select>
              </Field>
              <Field label="语言" hint="BCP-47。">
                <input
                  className={control}
                  value={replyLocale}
                  onChange={(e) => setReplyLocale(e.target.value)}
                />
              </Field>
              <Field label="套用模板" hint="留空则手写正文。">
                <select
                  className={control}
                  value={templateId}
                  onChange={(e) => setTemplateId(e.target.value)}
                >
                  <option value="">（手写正文）</option>
                  {(templates?.items ?? [])
                    .filter((template) => template.status === 'ACTIVE')
                    .map((template) => (
                      <option key={template.id} value={template.id}>
                        {template.templateKey} 第 {template.version} 版 ·{' '}
                        {replyTypeLabel(template.replyType)} ·{' '}
                        {reviewScopeLabel(template.ratingScope)}
                      </option>
                    ))}
                </select>
              </Field>
              <Field label="正文" hint="套了模板就留空；手写时这里必填。">
                <textarea
                  className={`${control} min-h-24`}
                  value={replyBody}
                  onChange={(e) => setReplyBody(e.target.value)}
                  disabled={Boolean(templateId)}
                />
              </Field>
            </div>
            <Button onClick={() => void submitReply()} size="sm" disabled={busy || !targetReviewId}>
              <MessageSquare className="mr-1 h-4 w-4" />
              写入回复
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 筛选 */}
      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="平台">
            <input
              className={control}
              value={platformCode}
              onChange={(e) => setPlatformCode(e.target.value.toUpperCase())}
              placeholder="AMAZON"
            />
          </Field>
          <Field label="评价档位">
            <select
              className={control}
              value={reviewScope}
              onChange={(e) => setReviewScope(e.target.value)}
            >
              <option value="">全部档位</option>
              <option value="POSITIVE">好评（4-5 星）</option>
              <option value="NEUTRAL">中评（3 星）</option>
              <option value="NEGATIVE">差评（1-2 星）</option>
            </select>
          </Field>
          <Field label="回复形态">
            <select
              className={control}
              value={replyType}
              onChange={(e) => setReplyType(e.target.value)}
            >
              <option value="">全部形态</option>
              <option value="PUBLIC_REPLY">公开回复</option>
              <option value="SELLER_COMMENT_APPEND">卖家评论追加</option>
            </select>
          </Field>
          <Field label="状态">
            <select className={control} value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">全部状态</option>
              <option value="PENDING">待投递</option>
              <option value="DELIVERED">平台已确认</option>
              <option value="REJECTED">平台拒绝</option>
              <option value="CANCELLED">已撤回</option>
            </select>
          </Field>
          <Field label="关键字" hint="回复号 / 评价号 / 正文 / 批次号。">
            <input
              className={control}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </Field>
        </div>
        <Button className="mt-3" onClick={() => setPageIndex(0)} size="sm" variant="secondary">
          <RefreshCw className="mr-1 h-4 w-4" />
          刷新
        </Button>
      </SectionCard>

      {/* ---------------------------------------------------------- 回复记录 */}
      <SectionCard title="回复记录">
        {!replies || replies.items.length === 0 ? (
          <EmptyState
            title={hasFilter ? '当前筛选没有命中任何回复记录' : '还没有回复记录'}
            description={
              hasFilter
                ? '换个筛选条件再试；或者先在上面写一条回复。'
                : '写一条回复就会出现在这里。'
            }
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {replies.items.map((reply: CrmReviewReplyRow) => (
              <li key={reply.id} className="border-border/60 space-y-1 border-b pb-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={reply.status === 'DELIVERED' ? 'success' : 'warning'}>
                    {replyStatusLabel(reply.status)}
                  </Badge>
                  <span className="font-medium">{reply.replyNo}</span>
                  <span className="text-muted-foreground">
                    {reply.platformCode} · {reply.shopName} · 评价 {reply.reviewNo}（
                    {reply.reviewRating} 星 · {reviewScopeLabel(reply.reviewScope)}） ·{' '}
                    {replyTypeLabel(reply.replyType)} · {reply.locale}
                  </span>
                  <span className="text-muted-foreground">{templateVersionLabel(reply)}</span>
                  {reply.batchNo && (
                    <span className="text-muted-foreground">批次 {reply.batchNo}</span>
                  )}
                </div>
                <p className="text-muted-foreground">{reply.body}</p>
                {reply.lastDeliveryNote && (
                  <p className="text-muted-foreground text-copy-12">
                    最近一次投递：{deliveryOutcomeLabel('NOT_INTEGRATED')} ——{' '}
                    {reply.lastDeliveryNote}
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="ghost" onClick={() => void openDetail(reply.id)}>
                    投递历史
                  </Button>
                  {writable && reply.status === 'PENDING' && (
                    <>
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={busy}
                        onClick={() => void runDeliver(reply.id)}
                      >
                        <Send className="mr-1 h-4 w-4" />
                        投递到平台
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void runCancel(reply.id)}
                      >
                        <XCircle className="mr-1 h-4 w-4" />
                        撤回
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
        {replies && replies.totalPages > 1 && (
          <div className="mt-3 flex gap-2">
            <Button
              size="sm"
              variant="secondary"
              disabled={pageIndex <= 0}
              onClick={() => setPageIndex((value) => Math.max(0, value - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-copy-12 self-center">
              第 {replies.page + 1} / {replies.totalPages} 页 · 共 {replies.totalElements} 条
            </span>
            <Button
              size="sm"
              variant="secondary"
              disabled={pageIndex + 1 >= replies.totalPages}
              onClick={() => setPageIndex((value) => value + 1)}
            >
              下一页
            </Button>
          </div>
        )}

        {detail && (
          <div className="text-copy-13 mt-3 space-y-2">
            <p className="font-medium">
              投递历史 · {detail.reply.replyNo}（{replyStatusLabel(detail.reply.status)}）
            </p>
            {detail.deliveries.length === 0 ? (
              <p className="text-muted-foreground text-copy-12">还没有投递尝试记录。</p>
            ) : (
              <ul className="text-copy-12 space-y-1">
                {detail.deliveries.map((delivery) => (
                  <li key={delivery.id} className="flex flex-wrap items-center gap-2">
                    <Badge tone={delivery.outcome === 'DELIVERED' ? 'success' : 'warning'}>
                      {deliveryOutcomeLabel(delivery.outcome)}
                    </Badge>
                    <span className="text-muted-foreground">
                      第 {delivery.attemptNo} 次 · {delivery.attemptedAt}
                    </span>
                    {delivery.note && (
                      <span className="text-muted-foreground">{delivery.note}</span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 批量回复 */}
      <SectionCard title="批量回复同类评价">
        <p className="text-muted-foreground text-copy-12 mb-3">
          「同类」的判定口径：<strong>同平台 × 同店铺 × 同档位 × 同形态 × 同语言</strong>。
          已经回过的评价会自动跳过；<strong>被跳过的每一条都带原因</strong> ——
          只报成功数会让人以为「这一批全都回上了」。
        </p>
        {writable && (
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="平台">
              <input
                className={control}
                value={batchPlatform}
                onChange={(e) => setBatchPlatform(e.target.value.toUpperCase())}
              />
            </Field>
            <Field label="回复形态">
              <select
                className={control}
                value={batchReplyType}
                onChange={(e) => setBatchReplyType(e.target.value)}
              >
                <option value="PUBLIC_REPLY">公开回复</option>
                <option value="SELLER_COMMENT_APPEND">卖家评论追加</option>
              </select>
            </Field>
            <Field label="评价档位">
              <select
                className={control}
                value={batchScope}
                onChange={(e) => setBatchScope(e.target.value)}
              >
                <option value="POSITIVE">好评（4-5 星）</option>
                <option value="NEUTRAL">中评（3 星）</option>
                <option value="NEGATIVE">差评（1-2 星）</option>
                <option value="ALL">不限档位</option>
              </select>
            </Field>
            <Field label="语言">
              <input
                className={control}
                value={batchLocale}
                onChange={(e) => setBatchLocale(e.target.value)}
              />
            </Field>
            <Field label="模板" hint="只列已生效的模板。">
              <select
                className={control}
                value={batchTemplateId}
                onChange={(e) => setBatchTemplateId(e.target.value)}
              >
                <option value="">（选一个已生效的模板）</option>
                {(templates?.items ?? [])
                  .filter((template) => template.status === 'ACTIVE')
                  .map((template) => (
                    <option key={template.id} value={template.id}>
                      {template.templateKey} 第 {template.version} 版
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="最多处理条数">
              <input
                className={control}
                value={batchLimit}
                onChange={(e) => setBatchLimit(e.target.value)}
              />
            </Field>
          </div>
        )}
        {writable && (
          <Button
            className="mt-3"
            onClick={() => void runBatch()}
            size="sm"
            disabled={busy || !batchTemplateId}
          >
            <CheckCircle2 className="mr-1 h-4 w-4" />
            执行批量回复
          </Button>
        )}

        {batches && (
          <div className="text-copy-13 mt-3 space-y-2">
            <p className="font-medium">
              批次 {batches.batch.batchNo} · {batches.batch.platformCode} ·{' '}
              {replyTypeLabel(batches.batch.replyType)} ·{' '}
              {reviewScopeLabel(batches.batch.ratingScope)} · 目标 {batches.batch.targetCount} 条 /
              写成 {batches.batch.createdCount} 条 / 跳过 {batches.batch.skippedCount} 条
            </p>
            {batches.note && <p className="text-muted-foreground">{batches.note}</p>}
            {batches.skipped.length > 0 && (
              <ul className="text-copy-12 space-y-1">
                {batches.skipped.map((skipped) => (
                  <li key={skipped.reviewId}>
                    <span className="font-medium">{skipped.reviewNo}</span> —— {skipped.reason}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 把后端抛出的原因取出来（ApiError 带 detail；其它错误取 message）。 */
function errorText(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = String((error as { message: unknown }).message ?? '');
    if (message) return message;
  }
  return fallback;
}
