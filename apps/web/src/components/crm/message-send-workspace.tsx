'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { Inbox, RefreshCw, RotateCcw, Search, Send, TriangleAlert } from 'lucide-react';
import type {
  CrmMessageSendBatchResult,
  CrmMessageSendCapabilities,
  CrmMessageSendDetail,
  CrmMessageSendKnowledgeTemplate,
  CrmMessageSendOutcome,
  CrmMessageSendPage,
  CrmMessageSendTargetPage,
  CrmMessageSendTemplatePage,
  CrmMessageSendTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  bodySourceLabel,
  createMessageSend,
  createMessageSendTemplate,
  deleteMessageSendTemplate,
  fetchMessageSendCapabilities,
  fetchMessageSendKnowledgeTemplates,
  fetchMessageSendTemplates,
  fetchMessageSendTargets,
  fetchMessageSends,
  fetchMessageSend,
  hasActiveSendFilter,
  newIdempotencyKey,
  parseSendAttachmentDraft,
  registerMessageSendResult,
  repliedStateLabel,
  retryMessageSend,
  runMessageSendBatch,
  sendOutcomeText,
  sendStatusLabel,
  sendSummaryText,
  targetStateText,
  templateVariablesText,
  toAttachmentInputs,
  transportLabel,
  updateMessageSendTemplate,
  type MessageSendParams,
} from './message-send-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.1.1.2 消息发送工作区。
 *
 * <p>页面按「先看清边界、再挑目标、再写回复、最后看记录」的顺序排，因为蓝图加粗句是
 * 「<strong>发送失败要保留原消息状态</strong>，不能让客服以为已回复」。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台侧站内信发送接口、15.1.1.3 分类、15.5.1 自动回复、11 售后、15.4 工单、
 *       邮件同会话这六块是 available=false，页面把它们<b>显式灰掉</b>并写清归属模块。</li>
 *   <li><b>可回复的目标</b> —— 来自 15.1.1.1 收件箱，每行都带<b>推导出来的</b>已回复状态；
 *       「最近一次尝试失败」与「没回过」<b>分开显示</b>。</li>
 *   <li><b>写回复</b> —— 调用模板 / 引用知识库话术 / 手工编辑，三选一；
 *       附件一行一个；<b>幂等键自动生成</b>（连点两次不会发两遍）。</li>
 *   <li><b>批量相同回复</b> —— 同一个正文发给多条消息，<b>逐条</b>给出结论与原因。</li>
 *   <li><b>发送记录</b> —— 记录 + 平台回执 + 失败原因 + 每次尝试的留痕；可重试、可回登记结果。</li>
 * </ul>
 */
export function MessageSendWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.message.send.update');

  const [capabilities, setCapabilities] = useState<CrmMessageSendCapabilities | null>(null);
  const [templates, setTemplates] = useState<CrmMessageSendTemplatePage | null>(null);
  const [knowledge, setKnowledge] = useState<CrmMessageSendKnowledgeTemplate[]>([]);
  const [targets, setTargets] = useState<CrmMessageSendTargetPage | null>(null);
  const [page, setPage] = useState<CrmMessageSendPage | null>(null);
  const [detail, setDetail] = useState<CrmMessageSendDetail | null>(null);
  const [batch, setBatch] = useState<CrmMessageSendBatchResult | null>(null);

  // 发送记录筛选
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 目标筛选
  const [targetKeyword, setTargetKeyword] = useState('');
  const [onlyUnreplied, setOnlyUnreplied] = useState(true);

  // 回复草稿
  const [draftTargetId, setDraftTargetId] = useState('');
  const [draftTemplateId, setDraftTemplateId] = useState('');
  const [draftKnowledgeVersionId, setDraftKnowledgeVersionId] = useState('');
  const [draftSubject, setDraftSubject] = useState('');
  const [draftBody, setDraftBody] = useState('');
  const [draftAttachments, setDraftAttachments] = useState('');
  const [draftKey, setDraftKey] = useState(() => newIdempotencyKey());

  // 批量草稿
  const [selected, setSelected] = useState<string[]>([]);
  const [batchBody, setBatchBody] = useState('');
  const [batchKey, setBatchKey] = useState(() => newIdempotencyKey('batch'));

  // 模板草稿
  const [templateCode, setTemplateCode] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [templateLocale, setTemplateLocale] = useState('zh-CN');
  const [templateSubject, setTemplateSubject] = useState('');
  const [templateBody, setTemplateBody] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const params = useMemo<MessageSendParams>(
    () => ({
      status: status || undefined,
      keyword: keyword || undefined,
      page: pageIndex,
      size: 20,
    }),
    [status, keyword, pageIndex],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, templateList, knowledgeList, targetList, result] = await Promise.all([
        fetchMessageSendCapabilities(),
        fetchMessageSendTemplates(),
        fetchMessageSendKnowledgeTemplates(),
        fetchMessageSendTargets({
          keyword: targetKeyword || undefined,
          onlyUnreplied: onlyUnreplied || undefined,
          size: 50,
        }),
        fetchMessageSends(params),
      ]);
      setCapabilities(caps);
      setTemplates(templateList);
      setKnowledge(knowledgeList);
      setTargets(targetList);
      setPage(result);
    } catch (e) {
      setError(errorText(e, '消息发送工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [params, targetKeyword, onlyUnreplied]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (sendId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchMessageSend(sendId));
    } catch (e) {
      setActionError(errorText(e, '发送记录详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  /** 登记一次发送（幂等键在表单生命周期内保持不变，重试提交复用同一个键）。 */
  const submitSend = useCallback(async () => {
    if (!draftTargetId) {
      setActionError('先在上面挑一条要回复的消息。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const outcome: CrmMessageSendOutcome = await createMessageSend({
        targetMessageId: draftTargetId,
        templateId: draftTemplateId || null,
        knowledgeVersionId: draftKnowledgeVersionId || null,
        subject: draftSubject,
        body: draftBody,
        transport: 'MANUAL_HANDOFF',
        status: 'QUEUED',
        failureReason: '',
        platformReceipt: '',
        platformMessageId: '',
        attachments: toAttachmentInputs(parseSendAttachmentDraft(draftAttachments)),
        idempotencyKey: draftKey,
        note: '',
      });
      setNotice(sendOutcomeText(outcome));
      setDraftBody('');
      setDraftAttachments('');
      // 换一份表单 ⇒ 换一把幂等键。
      setDraftKey(newIdempotencyKey());
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记发送失败'));
    } finally {
      setBusy(false);
    }
  }, [
    draftTargetId,
    draftTemplateId,
    draftKnowledgeVersionId,
    draftSubject,
    draftBody,
    draftAttachments,
    draftKey,
    load,
  ]);

  /** 批量相同回复：逐条给出结论，失败的条目不会被悄悄跳过。 */
  const submitBatch = useCallback(async () => {
    if (selected.length === 0) {
      setActionError('先勾选要批量回复的消息。');
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await runMessageSendBatch({
        messageIds: selected,
        templateId: null,
        knowledgeVersionId: null,
        subject: '',
        body: batchBody,
        transport: 'MANUAL_HANDOFF',
        attachments: [],
        idempotencyKey: batchKey,
        note: '',
      });
      setBatch(result);
      setNotice(
        `批量相同回复完成：共 ${result.requestedCount} 条，成功 ${result.succeededCount} 条，` +
          `失败 ${result.failedCount} 条。`,
      );
      setSelected([]);
      setBatchKey(newIdempotencyKey('batch'));
      await load();
    } catch (e) {
      setActionError(errorText(e, '批量相同回复失败'));
    } finally {
      setBusy(false);
    }
  }, [selected, batchBody, batchKey, load]);

  /** 回登记投递结果（人工交接：运营在平台侧实际发出或失败之后回登记）。 */
  const registerResult = useCallback(
    async (sendId: string, resultStatus: 'SENT' | 'FAILED') => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const outcome = await registerMessageSendResult(sendId, {
          status: resultStatus,
          failureReason: resultStatus === 'FAILED' ? '运营登记：平台侧发送失败' : '',
          platformReceipt: '',
          platformMessageId: '',
          operatorNote: '运营在平台侧手工登记',
        });
        setNotice(sendOutcomeText(outcome));
        await openDetail(sendId);
        await load();
      } catch (e) {
        setActionError(errorText(e, '登记投递结果失败'));
      } finally {
        setBusy(false);
      }
    },
    [openDetail, load],
  );

  const retry = useCallback(
    async (sendId: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const outcome = await retryMessageSend(sendId, { operatorNote: '客服重试' });
        setNotice(sendOutcomeText(outcome));
        await openDetail(sendId);
        await load();
      } catch (e) {
        setActionError(errorText(e, '重试失败'));
      } finally {
        setBusy(false);
      }
    },
    [openDetail, load],
  );

  /** 新建 / 更新模板。 */
  const saveTemplate = useCallback(
    async (existing?: CrmMessageSendTemplateRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const input = {
          templateCode,
          name: templateName,
          locale: templateLocale,
          subject: templateSubject,
          body: templateBody,
          shopId: null,
          platformCode: '',
          status: 'ACTIVE',
          note: '',
        };
        if (existing) {
          await updateMessageSendTemplate(existing.id, input);
          setNotice(
            `模板 ${templateCode} 已更新（历史发送记录不受影响 —— 它们快照了当时的话术）。`,
          );
        } else {
          await createMessageSendTemplate(input);
          setNotice(`模板 ${templateCode} 已创建。`);
        }
        setTemplateCode('');
        setTemplateName('');
        setTemplateSubject('');
        setTemplateBody('');
        await load();
      } catch (e) {
        setActionError(errorText(e, '保存模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [templateCode, templateName, templateLocale, templateSubject, templateBody, load],
  );

  const removeTemplate = useCallback(
    async (row: CrmMessageSendTemplateRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteMessageSendTemplate(row.id);
        setNotice(
          `模板 ${row.templateCode} 已处理：若它已被发送记录引用，后端会改成**停用**而不是删除 —— ` +
            '删掉之后历史发送记录上的模板引用会指向不存在的模板，「当时用的哪套话术」就再也读不出来了。',
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '删除模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  if (loading) {
    return (
      <SectionCard title="消息发送">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  const targetItems = targets?.items ?? [];
  const sendItems = page?.items ?? [];
  const filtered = hasActiveSendFilter(params);
  const templateItems = templates?.items ?? [];
  const platformConnected =
    capabilities?.dimensions.find((d) => d.dimension === 'platform_message_api')?.available ??
    false;

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
                  （归属模块 {dimension.ownerModule}）—— {dimension.impact}
                </span>
              )}
            </li>
          ))}
        </ul>
        {capabilities?.highlight && (
          <p className="text-muted-foreground text-copy-12 mt-3">{capabilities.highlight}</p>
        )}
        {capabilities?.boundaryNote && (
          <p className="text-muted-foreground text-copy-12 mt-2">{capabilities.boundaryNote}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 平台投递状态 */}
      <SectionCard title="平台投递状态">
        <p className="text-copy-13">
          <Badge tone={platformConnected ? 'success' : 'warning'}>
            {platformConnected ? '已接入' : '未接入'}
          </Badge>{' '}
          平台侧站内信<b>发送</b>接口未接入（归属模块 2）⇒ 投递只允许
          <strong>人工交接</strong>：在平台侧实际发出后回登记结果与平台回执。 本页
          <strong>不提供</strong>「发到平台」按钮 —— 一个点了就成功的假按钮会让运营以为
          买家已经收到回复了，而现场一条都没发出去。
        </p>
      </SectionCard>

      {/* ---------------------------------------------------------- 可回复的目标 */}
      <SectionCard title="可回复的消息（来自 15.1.1.1 收件箱）">
        <div className="mb-3 flex flex-wrap items-end gap-2">
          <div className="min-w-60 flex-1">
            <Field label="关键字（主题 / 正文 / 买家 / 订单）">
              <input
                className={control}
                value={targetKeyword}
                onChange={(e) => setTargetKeyword(e.target.value)}
                placeholder="买家或订单号"
              />
            </Field>
          </div>
          <label className="text-copy-13 flex items-center gap-2">
            <input
              type="checkbox"
              checked={onlyUnreplied}
              onChange={(e) => setOnlyUnreplied(e.target.checked)}
            />
            只看未回复的
          </label>
          <Button type="button" variant="secondary" onClick={() => void load()} disabled={busy}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>

        {targetItems.length === 0 ? (
          <EmptyState
            icon={<Inbox className="h-7 w-7" />}
            title={
              targetKeyword || onlyUnreplied ? '当前条件下没有可回复的消息' : '还没有可回复的消息'
            }
            description={
              targetKeyword || onlyUnreplied
                ? '把筛选条件放宽一点再试 —— 这不代表收件箱里没有消息。' +
                  '注意「只看未回复的」只按「有没有一条已发出的发送记录」判定。'
                : '消息来自 15.1.1.1 消息收件箱；那边还没有消息时，这里自然是空的。'
            }
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {targetItems.map((target) => (
              <li
                key={target.messageId}
                className="border-border flex flex-wrap items-start gap-2 rounded-md border px-3 py-2"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(target.messageId)}
                  onChange={(e) =>
                    setSelected((prev) =>
                      e.target.checked
                        ? [...prev, target.messageId]
                        : prev.filter((id) => id !== target.messageId),
                    )
                  }
                  disabled={!writable}
                />
                <span className="font-medium">{target.subject || '（无主题）'}</span>
                <span className="text-muted-foreground">
                  {target.messageNo} · {target.shopName} · {target.buyerReference} ·{' '}
                  {target.orderReference}
                </span>
                <Badge tone={target.repliedState === 'REPLIED' ? 'success' : 'warning'}>
                  {repliedStateLabel(target.repliedState)}
                </Badge>
                {target.lastSendStatus === 'FAILED' && (
                  <Badge tone="warning">
                    最近一次尝试失败：{target.lastFailureReason || '未写明原因'}
                  </Badge>
                )}
              </li>
            ))}
          </ul>
        )}
        <p className="text-muted-foreground text-copy-12 mt-3">
          未回复数 {targets?.unrepliedCount ?? 0} 条。已回复状态是<b>推导</b>出来的
          （存在一条「已发出」的发送记录）—— 发送失败<b>不会</b>把它变成已回复。
        </p>
      </SectionCard>

      {/* ---------------------------------------------------------- 写回复 */}
      <SectionCard title="写一条回复">
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="回复哪一条" hint="只能回复收到的消息；回复自己发出去的那条没有意义。">
              <select
                className={control}
                value={draftTargetId}
                onChange={(e) => setDraftTargetId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（选择一条要回复的消息）</option>
                {targetItems.map((target) => (
                  <option key={target.messageId} value={target.messageId}>
                    {target.messageNo} · {target.buyerReference} · {target.orderReference}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="调用模板（可选）"
              hint="选了模板会渲染模板正文；下面又填了正文就以你填的为准（= 模板起稿 + 手工编辑）。"
            >
              <select
                className={control}
                value={draftTemplateId}
                onChange={(e) => setDraftTemplateId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（不用模板，纯手工写）</option>
                {templateItems
                  .filter((t) => t.status === 'ACTIVE')
                  .map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.templateCode} · {t.name}
                    </option>
                  ))}
              </select>
            </Field>
            <Field
              label="引用知识库话术（可选）"
              hint="只列此刻生效的版本：草稿版本没有生效过，拿它答复买家等于引用一段从未给过买家的内容。"
            >
              <select
                className={control}
                value={draftKnowledgeVersionId}
                onChange={(e) => setDraftKnowledgeVersionId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（不引用知识库）</option>
                {knowledge.map((k) => (
                  <option key={k.versionId} value={k.versionId}>
                    v{k.versionNo} · {k.title}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="主题">
              <input
                className={control}
                value={draftSubject}
                onChange={(e) => setDraftSubject(e.target.value)}
                disabled={!writable}
              />
            </Field>
          </div>

          <Field
            label="正文"
            hint="留空会用模板或知识库的正文；两处都留空会被服务端拒绝（不留一条空回复的发送记录）。"
          >
            <textarea
              className={`${control} min-h-28`}
              value={draftBody}
              onChange={(e) => setDraftBody(e.target.value)}
              disabled={!writable}
            />
          </Field>

          <Field
            label="附件（一行一个：文件名 | 链接）"
            hint="附件字节归平台侧与 8 仓储，本服务只记元数据；文件名缺失的行会被服务端拒绝，不会被悄悄丢掉。"
          >
            <textarea
              className={`${control} min-h-20`}
              value={draftAttachments}
              onChange={(e) => setDraftAttachments(e.target.value)}
              placeholder={'invoice.pdf | https://cdn/invoice.pdf'}
              disabled={!writable}
            />
          </Field>

          <Field
            label="幂等键"
            hint="同一条键重放不会产生第二条发送记录 —— 连点两次不会给买家发两遍。"
          >
            <input
              className={control}
              value={draftKey}
              onChange={(e) => setDraftKey(e.target.value)}
              disabled={!writable}
            />
          </Field>

          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" onClick={() => void submitSend()} disabled={busy || !writable}>
              <Send className="h-4 w-4" />
              登记发送（人工交接）
            </Button>
            <span className="text-muted-foreground text-copy-12">
              登记后是「待发」；到平台侧真的发出去之后，在下面的发送记录里回登记结果。
            </span>
          </div>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 批量相同回复 */}
      <SectionCard title="批量相同回复（同类问题）">
        <div className="space-y-3">
          <Field
            label="同一句回复"
            hint={`已勾选 ${selected.length} 条消息。逐条给出结论与原因，失败的条目不会被悄悄跳过。`}
          >
            <textarea
              className={`${control} min-h-24`}
              value={batchBody}
              onChange={(e) => setBatchBody(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Field label="批次幂等键" hint="重放同一批不会发第二遍。">
            <input
              className={control}
              value={batchKey}
              onChange={(e) => setBatchKey(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Button
            type="button"
            variant="secondary"
            onClick={() => void submitBatch()}
            disabled={busy || !writable || selected.length === 0}
          >
            <Send className="h-4 w-4" />
            批量发给已勾选的 {selected.length} 条
          </Button>

          {batch && (
            <div className="text-copy-13 space-y-2">
              <p>
                批次 {batch.batchId}：共 {batch.requestedCount} 条，成功 {batch.succeededCount} 条，
                失败 {batch.failedCount} 条。
              </p>
              <ul className="space-y-1">
                {batch.items.map((item) => (
                  <li key={item.messageId} className="flex flex-wrap items-start gap-2">
                    <Badge tone={item.success ? 'success' : 'warning'}>
                      {item.success ? '成功' : '失败'}
                    </Badge>
                    <span className="font-medium">{item.messageNo || item.messageId}</span>
                    <span className="text-muted-foreground">{item.reason}</span>
                  </li>
                ))}
              </ul>
              <p className="text-muted-foreground text-copy-12">{batch.boundaryNote}</p>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 发送记录 */}
      <SectionCard title="发送记录">
        <div className="mb-3 flex flex-wrap items-end gap-2">
          <div className="min-w-40">
            <Field label="状态">
              <select
                className={control}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">（全部）</option>
                {(capabilities?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {sendStatusLabel(s)}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div className="min-w-60 flex-1">
            <Field label="关键字（发送号 / 主题 / 正文 / 买家 / 订单）">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
          </div>
        </div>

        <p className="text-muted-foreground text-copy-12 mb-2">{sendSummaryText(page?.summary)}</p>

        {sendItems.length === 0 ? (
          <EmptyState
            icon={<Search className="h-7 w-7" />}
            title={filtered ? '当前筛选条件下没有发送记录' : '还没有发送记录'}
            description={
              filtered
                ? '把筛选条件放宽一点再试 —— 这不代表没有发过消息。'
                : '在上面登记一条回复，或对同类问题做一次批量相同回复。'
            }
          />
        ) : (
          <ul className="text-copy-13 space-y-2">
            {sendItems.map((send) => (
              <li
                key={send.id}
                className="border-border flex flex-wrap items-start gap-2 rounded-md border px-3 py-2"
              >
                <Badge
                  tone={
                    send.status === 'SENT'
                      ? 'success'
                      : send.status === 'FAILED'
                        ? 'warning'
                        : 'neutral'
                  }
                >
                  {sendStatusLabel(send.status)}
                </Badge>
                <span className="font-medium">{send.sendNo}</span>
                <span className="text-muted-foreground">
                  {send.shopName} · {send.buyerReference} · {send.orderReference}
                </span>
                <Badge>{bodySourceLabel(send.bodySource)}</Badge>
                <Badge>{transportLabel(send.transport)}</Badge>
                <span className="text-muted-foreground">尝试 {send.attemptCount} 次</span>
                {send.status === 'FAILED' && (
                  <span className="text-destructive">失败原因：{send.failureReason}</span>
                )}
                <Button type="button" variant="ghost" onClick={() => void openDetail(send.id)}>
                  详情
                </Button>
                {send.status === 'FAILED' && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => void retry(send.id)}
                    disabled={busy || !writable}
                  >
                    <RotateCcw className="h-4 w-4" />
                    重试
                  </Button>
                )}
                {send.status === 'QUEUED' && (
                  <>
                    <Button
                      type="button"
                      variant="secondary"
                      onClick={() => void registerResult(send.id, 'SENT')}
                      disabled={busy || !writable}
                    >
                      回登记已发出
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => void registerResult(send.id, 'FAILED')}
                      disabled={busy || !writable}
                    >
                      回登记失败
                    </Button>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}

        <div className="text-copy-13 mt-3 flex items-center gap-2">
          <Button
            type="button"
            variant="secondary"
            disabled={pageIndex <= 0}
            onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
          >
            上一页
          </Button>
          <span className="text-muted-foreground">
            第 {pageIndex + 1} 页 · 共 {page?.total ?? 0} 条
          </span>
          <Button
            type="button"
            variant="secondary"
            disabled={(page?.items.length ?? 0) < (page?.size ?? 20)}
            onClick={() => setPageIndex((p) => p + 1)}
          >
            下一页
          </Button>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 发送记录详情 */}
      {detail && (
        <SectionCard title={`发送记录 ${detail.send.sendNo}`}>
          <div className="text-copy-13 space-y-3">
            <p>
              <Badge
                tone={
                  detail.send.status === 'SENT'
                    ? 'success'
                    : detail.send.status === 'FAILED'
                      ? 'warning'
                      : 'neutral'
                }
              >
                {sendStatusLabel(detail.send.status)}
              </Badge>{' '}
              {targetStateText(detail.target)}
            </p>
            <p>
              正文（{bodySourceLabel(detail.send.bodySource)}）：
              <span className="text-muted-foreground"> {detail.send.body}</span>
            </p>
            {detail.send.platformReceipt && (
              <p>
                平台回执：
                <span className="text-muted-foreground">{detail.send.platformReceipt}</span>
              </p>
            )}
            {detail.send.failureReason && (
              <p className="text-destructive">失败原因：{detail.send.failureReason}</p>
            )}
            {detail.attachments.length > 0 && (
              <div>
                <p className="font-medium">附件（{detail.attachments.length}）</p>
                <ul className="text-muted-foreground space-y-1">
                  {detail.attachments.map((attachment) => (
                    <li key={attachment.id}>
                      {attachment.fileName}
                      {attachment.fileUrl ? ` · ${attachment.fileUrl}` : ''}
                      {attachment.sizeBytes ? ` · ${attachment.sizeBytes} 字节` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div>
              <p className="font-medium">尝试留痕（{detail.attempts.length} 次）</p>
              <ul className="space-y-1">
                {detail.attempts.map((attempt) => (
                  <li key={attempt.id} className="flex flex-wrap items-start gap-2">
                    <Badge
                      tone={
                        attempt.status === 'SENT'
                          ? 'success'
                          : attempt.status === 'FAILED'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      第 {attempt.attemptNo} 次 · {sendStatusLabel(attempt.status)}
                    </Badge>
                    <span className="text-muted-foreground">
                      {attempt.attemptedAt}
                      {attempt.failureReason ? ` · ${attempt.failureReason}` : ''}
                      {attempt.platformReceipt ? ` · 回执 ${attempt.platformReceipt}` : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
            <p className="text-muted-foreground text-copy-12">
              <TriangleAlert className="mr-1 inline h-3 w-3" />
              发送记录里的「已回复」是由「存在一条已发出的发送记录」推导的； 本模块
              <b>从不改写源消息</b> —— 发送失败时那条消息仍然是「未回复」。
            </p>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 模板管理 */}
      <SectionCard title="回复模板">
        <div className="space-y-3">
          <p className="text-muted-foreground text-copy-12">
            {templateVariablesText(capabilities?.templateVariables)}
          </p>
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="模板编码" hint="同租户内唯一，运营按编码引用。">
              <input
                className={control}
                value={templateCode}
                onChange={(e) => setTemplateCode(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="模板名">
              <input
                className={control}
                value={templateName}
                onChange={(e) => setTemplateName(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="语言" hint="BCP-47 简写，例如 zh-CN / en-US。">
              <input
                className={control}
                value={templateLocale}
                onChange={(e) => setTemplateLocale(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="主题">
              <input
                className={control}
                value={templateSubject}
                onChange={(e) => setTemplateSubject(e.target.value)}
                disabled={!writable}
              />
            </Field>
          </div>
          <Field
            label="模板正文"
            hint="可以用 {{buyer_name}} {{order_no}} 这类变量；未定义的变量会让发送失败。"
          >
            <textarea
              className={`${control} min-h-24`}
              value={templateBody}
              onChange={(e) => setTemplateBody(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Button
            type="button"
            onClick={() => void saveTemplate()}
            disabled={busy || !writable || !templateCode || !templateName}
          >
            新建模板
          </Button>

          {templateItems.length === 0 ? (
            <p className="text-muted-foreground text-copy-13">还没有模板 —— 可以先手工写回复。</p>
          ) : (
            <ul className="text-copy-13 space-y-2">
              {templateItems.map((row) => (
                <li
                  key={row.id}
                  className="border-border flex flex-wrap items-start gap-2 rounded-md border px-3 py-2"
                >
                  <Badge tone={row.status === 'ACTIVE' ? 'success' : 'warning'}>
                    {row.status === 'ACTIVE' ? '可用' : '已停用'}
                  </Badge>
                  <span className="font-medium">{row.templateCode}</span>
                  <span className="text-muted-foreground">
                    {row.name} · {row.locale}
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => {
                      setTemplateCode(row.templateCode);
                      setTemplateName(row.name);
                      setTemplateLocale(row.locale);
                      setTemplateSubject(row.subject);
                      setTemplateBody(row.body);
                    }}
                  >
                    载入编辑
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => void saveTemplate(row)}
                    disabled={busy || !writable}
                  >
                    保存改动
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => void removeTemplate(row)}
                    disabled={busy || !writable}
                  >
                    删除 / 停用
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
