'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { AlertTriangle, Inbox, Mail, Paperclip, RefreshCw, Send, Trash2 } from 'lucide-react';
import type {
  CrmBuyerEmailCapabilities,
  CrmBuyerEmailDetail,
  CrmBuyerEmailPage,
  CrmBuyerEmailRow,
  CrmBuyerEmailSendResult,
  CrmBuyerEmailShopView,
  CrmBuyerEmailTemplatePage,
  CrmBuyerEmailTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  createBuyerEmail,
  createBuyerEmailAttachment,
  createBuyerEmailSendRecord,
  createBuyerEmailTemplate,
  deleteBuyerEmailTemplate,
  emailDirectionLabel,
  emailSourceLabel,
  emailStatusLabel,
  fetchBuyerEmail,
  fetchBuyerEmailCapabilities,
  fetchBuyerEmailShops,
  fetchBuyerEmailTemplates,
  fetchBuyerEmails,
  repliedStateLabel,
  sendStatusLabel,
  syncBuyerEmails,
  templateStatusLabel,
  transportLabel,
  updateBuyerEmailTemplate,
} from './buyer-email-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.1.2.1 邮件收发工作区。
 *
 * <p>页面按「先看清边界、再看信、后回信」的顺序排，因为蓝图加粗句是
 * 「**部分平台要求邮件与站内信在同一会话处理，分开展示会导致重复回复买家**」——
 * 界面上第一件要让人看到的就是**能力边界**与**会话**，
 * 而不是一张可以直接点「批量回复」的按钮。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台买家邮件同步、平台邮件发送接口、站内信原文三块是 available=false，
 *       页面把它们**显式灰掉**并写清归属模块，不给任何看起来能点的假入口。</li>
 *   <li><b>邮件列表</b> —— 真分页 + 真筛选（店铺 / 方向 / 状态 / 买家 / 订单 / 会话键 / 主题 / 时间窗），
 *       空态区分「筛选无结果」与「收件箱还是空的」。</li>
 *   <li><b>邮件详情</b> —— 正文 + 附件元数据 + 发送记录 + **同会话邮件**。
 *       收到的与发出的在同一个会话里，客服一眼能看出「回过没有」。</li>
 *   <li><b>登记收到的邮件</b> —— 平台适配器未接入时，这是唯一能进邮件的入口。</li>
 *   <li><b>发送记录</b> —— 只允许「人工交接」；<b>失败保留原邮件状态</b>，
 *       失败原因与平台回执都留痕。</li>
 *   <li><b>邮件模板</b> —— 本模块自己的模板 CRUD（不是 15.4 知识库，也不是 15.1.1.2 的消息模板）。</li>
 * </ul>
 */
export function BuyerEmailWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.email.update');

  const [capabilities, setCapabilities] = useState<CrmBuyerEmailCapabilities | null>(null);
  const [shops, setShops] = useState<CrmBuyerEmailShopView[]>([]);
  const [page, setPage] = useState<CrmBuyerEmailPage | null>(null);
  const [detail, setDetail] = useState<CrmBuyerEmailDetail | null>(null);
  const [templates, setTemplates] = useState<CrmBuyerEmailTemplatePage | null>(null);

  // 筛选
  const [shopId, setShopId] = useState('');
  const [direction, setDirection] = useState('');
  const [status, setStatus] = useState('');
  const [buyerRef, setBuyerRef] = useState('');
  const [salesOrderNo, setSalesOrderNo] = useState('');
  const [conversationKey, setConversationKey] = useState('');
  const [subject, setSubject] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 登记收到的邮件
  const [newShopId, setNewShopId] = useState('');
  const [newBuyerRef, setNewBuyerRef] = useState('');
  const [newBuyerName, setNewBuyerName] = useState('');
  const [newOrderNo, setNewOrderNo] = useState('');
  const [newSubject, setNewSubject] = useState('');
  const [newBody, setNewBody] = useState('');
  const [newReceivedAt, setNewReceivedAt] = useState('');

  // 发送记录
  const [sendStatus, setSendStatus] = useState('SENT');
  const [sendSubject, setSendSubject] = useState('');
  const [sendBody, setSendBody] = useState('');
  const [sendTemplateId, setSendTemplateId] = useState('');
  const [sendReceipt, setSendReceipt] = useState('');
  const [sendFailure, setSendFailure] = useState('');
  const [attachmentName, setAttachmentName] = useState('');
  const [attachmentType, setAttachmentType] = useState('');
  const [attachmentSize, setAttachmentSize] = useState('');

  // 模板
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

  /** 当前是否有筛选条件 —— 空态要区分「筛选无结果」与「收件箱还是空的」。 */
  const hasFilter = useMemo(
    () =>
      Boolean(
        shopId || direction || status || buyerRef || salesOrderNo || conversationKey || subject,
      ),
    [shopId, direction, status, buyerRef, salesOrderNo, conversationKey, subject],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, shopList, mailPage, templatePage] = await Promise.all([
        fetchBuyerEmailCapabilities(),
        fetchBuyerEmailShops(),
        fetchBuyerEmails({
          shopId: shopId || undefined,
          direction: direction || undefined,
          status: status || undefined,
          buyerRef: buyerRef || undefined,
          salesOrderNo: salesOrderNo || undefined,
          conversationKey: conversationKey || undefined,
          subject: subject || undefined,
          page: pageIndex,
          size: 20,
        }),
        fetchBuyerEmailTemplates({ size: 50 }),
      ]);
      setCapabilities(caps);
      setShops(shopList);
      setPage(mailPage);
      setTemplates(templatePage);
    } catch (e) {
      setError(errorText(e, '邮件工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [shopId, direction, status, buyerRef, salesOrderNo, conversationKey, subject, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (emailId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchBuyerEmail(emailId));
    } catch (e) {
      setActionError(errorText(e, '邮件详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitIncoming = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createBuyerEmail({
        shopId: newShopId,
        buyerRef: newBuyerRef,
        buyerName: newBuyerName,
        buyerEmail: '',
        subject: newSubject,
        body: newBody,
        salesOrderNo: newOrderNo,
        externalMessageId: '',
        conversationKey: '',
        sourceMessageId: null,
        receivedAt: newReceivedAt,
        note: '',
      });
      setNotice(`已登记邮件 ${created.emailNo}（会话键 ${created.conversationKey}）。`);
      setNewBuyerRef('');
      setNewSubject('');
      setNewBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记邮件失败'));
    } finally {
      setBusy(false);
    }
  }, [newShopId, newBuyerRef, newBuyerName, newOrderNo, newSubject, newBody, newReceivedAt, load]);

  const submitSend = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result: CrmBuyerEmailSendResult = await createBuyerEmailSendRecord(detail.email.id, {
        transport: 'MANUAL_HANDOFF',
        status: sendStatus,
        subject: sendSubject,
        body: sendBody,
        templateId: sendTemplateId || null,
        platformReceipt: sendReceipt,
        failureReason: sendFailure,
        attachments: null,
        note: '',
      });
      setNotice(result.note);
      setSendSubject('');
      setSendBody('');
      setSendReceipt('');
      setSendFailure('');
      await openDetail(detail.email.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记发送记录失败'));
    } finally {
      setBusy(false);
    }
  }, [
    detail,
    sendStatus,
    sendSubject,
    sendBody,
    sendTemplateId,
    sendReceipt,
    sendFailure,
    openDetail,
    load,
  ]);

  const submitAttachment = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createBuyerEmailAttachment(detail.email.id, {
        fileName: attachmentName,
        contentType: attachmentType,
        // ⚠️ 只做**格式转换**（输入串 → 契约里的 number），**不做任何前端算术**：
        //   字节数原样交给后端落 numeric(19,6)，页面不参与计算。
        sizeBytes: attachmentSize === '' ? 0 : Number(attachmentSize),
        storageKey: '',
        checksumSha256: '',
      });
      setNotice('已登记附件元数据（本服务不持有文件字节）。');
      setAttachmentName('');
      setAttachmentType('');
      setAttachmentSize('');
      await openDetail(detail.email.id);
    } catch (e) {
      setActionError(errorText(e, '登记附件失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, attachmentName, attachmentType, attachmentSize, openDetail]);

  const submitTemplate = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createBuyerEmailTemplate({
        templateCode,
        name: templateName,
        locale: templateLocale,
        subject: templateSubject,
        body: templateBody,
        shopId: null,
        status: 'ACTIVE',
        note: '',
      });
      setNotice(`已创建邮件模板 ${templateCode}。`);
      setTemplateCode('');
      setTemplateName('');
      setTemplateSubject('');
      setTemplateBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '创建模板失败'));
    } finally {
      setBusy(false);
    }
  }, [templateCode, templateName, templateLocale, templateSubject, templateBody, load]);

  const toggleTemplate = useCallback(
    async (template: CrmBuyerEmailTemplateRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await updateBuyerEmailTemplate(template.id, {
          templateCode: template.templateCode,
          name: template.name,
          locale: template.locale,
          subject: template.subject,
          body: template.body,
          shopId: template.shopId,
          status: template.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
          note: template.note,
        });
        setNotice(
          `模板 ${template.templateCode} 已${template.status === 'ACTIVE' ? '停用' : '启用'}。`,
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '改模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const removeTemplate = useCallback(
    async (template: CrmBuyerEmailTemplateRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteBuyerEmailTemplate(template.id);
        setNotice(`模板 ${template.templateCode} 已删除。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '删模板失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const runSync = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await syncBuyerEmails({ shopId: newShopId || shops[0]?.id || '' });
      setNotice(`已同步 ${result.synced} 封平台买家邮件。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '平台邮件同步未接入'));
    } finally {
      setBusy(false);
    }
  }, [newShopId, shops, load]);

  if (loading && !page) {
    return (
      <SectionCard title="买家邮件">
        <p className="text-muted-foreground text-copy-13">正在读取买家邮件……</p>
      </SectionCard>
    );
  }

  if (error && !page) {
    return (
      <SectionCard title="买家邮件">
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
      {/* ---------------------------------------------------------- 能力声明 */}
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
        {capabilities?.conversationKeySpec && (
          <p className="text-muted-foreground text-copy-12 mt-3">
            会话键口径：{capabilities.conversationKeySpec}
          </p>
        )}
        {capabilities?.highlight && (
          <p className="text-muted-foreground text-copy-12 mt-1">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-copy-13 rounded-md px-3 py-2">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 邮件列表 */}
      <SectionCard
        title="邮件列表"
        description="收到的与发出的都在这里；点一封看它的会话与发送记录。"
        extra={
          <Button onClick={() => void load()} size="sm" variant="secondary" disabled={busy}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="店铺">
              <select
                className={control}
                value={shopId}
                onChange={(e) => {
                  setShopId(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">（全部店铺）</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name} · {shop.platformCode}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="方向">
              <select
                className={control}
                value={direction}
                onChange={(e) => {
                  setDirection(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">（收 + 发）</option>
                <option value="INBOUND">收到的</option>
                <option value="OUTBOUND">发出的</option>
              </select>
            </Field>
            <Field label="状态">
              <select
                className={control}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">（全部状态）</option>
                <option value="RECEIVED">已收到（尚未回复）</option>
                <option value="REPLIED">已回复</option>
                <option value="QUEUED">已登记待发</option>
                <option value="SENT">已发出</option>
                <option value="FAILED">发送失败</option>
              </select>
            </Field>
            <Field label="买家标识">
              <input
                className={control}
                value={buyerRef}
                onChange={(e) => {
                  setBuyerRef(e.target.value);
                  setPageIndex(0);
                }}
                placeholder="例：AMZN-BUYER-8821"
              />
            </Field>
            <Field label="订单号">
              <input
                className={control}
                value={salesOrderNo}
                onChange={(e) => {
                  setSalesOrderNo(e.target.value);
                  setPageIndex(0);
                }}
                placeholder="例：SO-10001"
              />
            </Field>
            <Field label="会话键" hint="与站内信对齐用的键（15.1.1.1 落地后按同一公式对齐）。">
              <input
                className={control}
                value={conversationKey}
                onChange={(e) => {
                  setConversationKey(e.target.value);
                  setPageIndex(0);
                }}
              />
            </Field>
            <Field label="主题包含">
              <input
                className={control}
                value={subject}
                onChange={(e) => {
                  setSubject(e.target.value);
                  setPageIndex(0);
                }}
                placeholder="例：退款"
              />
            </Field>
          </div>

          {(page?.items.length ?? 0) === 0 ? (
            hasFilter ? (
              <EmptyState
                icon={<Inbox className="h-6 w-6" />}
                title="当前筛选没有匹配的邮件"
                description="把筛选条件放宽一点再试 —— 「筛选无结果」和「收件箱还是空的」是两件不同的事。"
              />
            ) : (
              <EmptyState
                icon={<Inbox className="h-6 w-6" />}
                title="收件箱还是空的"
                description="本租户还没有登记过买家邮件。平台买家邮件适配器未接入，邮件只能人工登记（见下方「登记收到的邮件」）。"
              />
            )
          ) : (
            <ul className="divide-border divide-y">
              {page?.items.map((row) => (
                <li key={row.id} className="py-2">
                  <button
                    type="button"
                    className="w-full text-left"
                    onClick={() => void openDetail(row.id)}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={row.direction === 'INBOUND' ? 'info' : 'neutral'}>
                        {emailDirectionLabel(row.direction)}
                      </Badge>
                      <Badge
                        tone={
                          row.status === 'FAILED'
                            ? 'danger'
                            : row.status === 'REPLIED' || row.status === 'SENT'
                              ? 'success'
                              : 'neutral'
                        }
                      >
                        {emailStatusLabel(row.status)}
                      </Badge>
                      <span className="font-medium">{row.subject || '（无主题）'}</span>
                      <span className="text-muted-foreground text-copy-12">
                        {row.emailNo} · {row.shopName} · {row.buyerRef}
                      </span>
                    </div>
                    <div className="text-muted-foreground text-copy-12 mt-1">
                      {row.receivedAt} · {emailSourceLabel(row.source)} · 附件 {row.attachmentCount}{' '}
                      · 发送记录 {row.sendRecordCount} · {repliedStateLabel(row)}
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {page && page.totalElements > 0 && (
            <div className="text-copy-12 flex items-center justify-between">
              <span className="text-muted-foreground">
                共 {page.totalElements} 封 · 第 {page.page + 1} / {Math.max(page.totalPages, 1)} 页
              </span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page.page <= 0 || busy}
                  onClick={() => setPageIndex(Math.max(page.page - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page.page + 1 >= page.totalPages || busy}
                  onClick={() => setPageIndex(page.page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 邮件详情 */}
      {detail && (
        <SectionCard
          title={`邮件详情 · ${detail.email.emailNo}`}
          description="同一个会话里的邮件都在下面 —— 收到与发出的放在一起，才能看出「回过没有」。"
          extra={
            <Button size="sm" variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          }
        >
          <div className="space-y-4">
            <div className="text-copy-13 space-y-1">
              <p>
                <strong>{detail.email.subject || '（无主题）'}</strong>
              </p>
              <p className="text-muted-foreground">
                {detail.email.shopName} · {detail.email.platformCode} · 买家{' '}
                {detail.email.buyerName || detail.email.buyerRef} · 平台时刻{' '}
                {detail.email.receivedAt}
              </p>
              <p className="text-muted-foreground">
                会话键 <code className="text-copy-12">{detail.email.conversationKey}</code> ·{' '}
                {repliedStateLabel(detail.email)}
              </p>
            </div>

            <div className="bg-muted/30 text-copy-13 whitespace-pre-wrap rounded-md px-3 py-2">
              {detail.email.body || '（正文为空）'}
            </div>

            <div>
              <h4 className="text-copy-13 font-medium">附件（{detail.attachments.length}）</h4>
              {detail.attachments.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">
                  没有附件。本服务只存附件**元数据**，不持有文件字节。
                </p>
              ) : (
                <ul className="text-copy-12 mt-1 space-y-1">
                  {detail.attachments.map((attachment) => (
                    <li key={attachment.id} className="flex flex-wrap items-center gap-2">
                      <Paperclip className="h-3 w-3" />
                      <span className="font-medium">{attachment.fileName}</span>
                      <span className="text-muted-foreground">
                        {attachment.contentType || '类型未登记'} · {attachment.sizeBytes} 字节
                        {attachment.storageKey ? ` · ${attachment.storageKey}` : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="text-copy-13 font-medium">发送记录（{detail.sendRecords.length}）</h4>
              {detail.sendRecords.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">还没有回过这封邮件。</p>
              ) : (
                <ul className="text-copy-12 mt-1 space-y-2">
                  {detail.sendRecords.map((record) => (
                    <li key={record.id} className="space-y-0.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={record.status === 'FAILED' ? 'danger' : 'neutral'}>
                          {sendStatusLabel(record.status)}
                        </Badge>
                        <span>{transportLabel(record.transport)}</span>
                        <span className="text-muted-foreground">{record.createdAt}</span>
                        {record.templateCode && (
                          <span className="text-muted-foreground">模板 {record.templateCode}</span>
                        )}
                      </div>
                      {record.platformReceipt && (
                        <div className="text-muted-foreground">
                          平台回执：{record.platformReceipt}
                        </div>
                      )}
                      {record.failureReason && (
                        <div className="text-destructive">失败原因：{record.failureReason}</div>
                      )}
                      {record.body && (
                        <div className="text-muted-foreground whitespace-pre-wrap">
                          {record.body}
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="text-copy-13 font-medium">同一会话（{detail.conversation.length}）</h4>
              <ul className="text-copy-12 mt-1 space-y-1">
                {detail.conversation.map((row) => (
                  <li key={row.id} className="flex flex-wrap items-center gap-2">
                    <Badge tone={row.direction === 'INBOUND' ? 'info' : 'neutral'}>
                      {emailDirectionLabel(row.direction)}
                    </Badge>
                    <span>{row.subject || '（无主题）'}</span>
                    <span className="text-muted-foreground">
                      {row.receivedAt} · {emailStatusLabel(row.status)}
                    </span>
                  </li>
                ))}
              </ul>
              <p className="text-muted-foreground text-copy-12 mt-1">
                站内信那一半暂时看不到（15.1.1.1 未交付），但同源已按会话键标记 ——
                它落地后按同一公式对齐即可。
              </p>
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 登记收到的邮件 */}
      <SectionCard
        title="登记收到的邮件"
        description="平台买家邮件适配器未接入，买家来信目前只能人工登记。登记后它会进入会话视图。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="店铺" hint="来自上游 channel 的真实店铺清单。">
              <select
                className={control}
                value={newShopId}
                onChange={(e) => setNewShopId(e.target.value)}
                disabled={!writable}
              >
                <option value="">（选择一家店铺）</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name} · {shop.platformCode}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="买家标识" hint="本仓没有买家档案（15.3.1.1 未交付），由运营按店铺提供。">
              <input
                className={control}
                value={newBuyerRef}
                onChange={(e) => setNewBuyerRef(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="买家名称">
              <input
                className={control}
                value={newBuyerName}
                onChange={(e) => setNewBuyerName(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="关联订单号" hint="留空则会话键第三段写 '-'。">
              <input
                className={control}
                value={newOrderNo}
                onChange={(e) => setNewOrderNo(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field label="主题">
              <input
                className={control}
                value={newSubject}
                onChange={(e) => setNewSubject(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Field
              label="平台时刻"
              hint="带时区偏移量，例如 2026-10-10T08:00:00+08:00（缺偏移量会被拒绝）。"
            >
              <input
                className={control}
                value={newReceivedAt}
                onChange={(e) => setNewReceivedAt(e.target.value)}
                placeholder="2026-10-10T08:00:00+08:00"
                disabled={!writable}
              />
            </Field>
          </div>
          <Field label="正文">
            <textarea
              className={`${control} min-h-24`}
              value={newBody}
              onChange={(e) => setNewBody(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Button
            onClick={() => void submitIncoming()}
            disabled={busy || !writable || !newShopId || !newBuyerRef || !newReceivedAt}
            size="sm"
          >
            <Mail className="mr-1 h-4 w-4" />
            登记邮件
          </Button>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 发送记录 */}
      <SectionCard
        title="登记发送记录"
        description="平台侧邮件发送接口未接入，只允许「人工交接」。发送失败保留原邮件状态 —— 失败原因与平台回执都留痕。"
      >
        {!detail ? (
          <EmptyState
            icon={<Send className="h-6 w-6" />}
            title="先选一封邮件"
            description="发送记录是挂在某一封收到的邮件上的。先在上方列表里点一封。"
          />
        ) : (
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-13">
              回复对象：<strong>{detail.email.emailNo}</strong>（{detail.email.subject || '无主题'}
              ） —— 当前状态 {emailStatusLabel(detail.email.status)}。
            </p>
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="发送结果">
                <select
                  className={control}
                  value={sendStatus}
                  onChange={(e) => setSendStatus(e.target.value)}
                  disabled={!writable}
                >
                  <option value="SENT">已发出</option>
                  <option value="QUEUED">已登记待发</option>
                  <option value="FAILED">发送失败（必须写原因）</option>
                  <option value="CANCELLED">已作废</option>
                </select>
              </Field>
              <Field label="引用模板" hint="留空则用下面的主题与正文；模板停用后不能引用。">
                <select
                  className={control}
                  value={sendTemplateId}
                  onChange={(e) => setSendTemplateId(e.target.value)}
                  disabled={!writable}
                >
                  <option value="">（不使用模板）</option>
                  {(templates?.items ?? [])
                    .filter((template) => template.status === 'ACTIVE')
                    .map((template) => (
                      <option key={template.id} value={template.id}>
                        {template.templateCode} · {template.name}
                      </option>
                    ))}
                </select>
              </Field>
              <Field label="平台回执" hint="人工交接时可把平台侧的回执号登记进来。">
                <input
                  className={control}
                  value={sendReceipt}
                  onChange={(e) => setSendReceipt(e.target.value)}
                  disabled={!writable}
                />
              </Field>
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="主题" hint="留空且没选模板时取模板的主题；两者都空会被拒绝。">
                <input
                  className={control}
                  value={sendSubject}
                  onChange={(e) => setSendSubject(e.target.value)}
                  disabled={!writable}
                />
              </Field>
              <Field label="失败原因" hint="选了「发送失败」就必填。">
                <input
                  className={control}
                  value={sendFailure}
                  onChange={(e) => setSendFailure(e.target.value)}
                  disabled={!writable}
                />
              </Field>
            </div>
            <Field label="正文">
              <textarea
                className={`${control} min-h-24`}
                value={sendBody}
                onChange={(e) => setSendBody(e.target.value)}
                disabled={!writable}
              />
            </Field>
            <Button
              onClick={() => void submitSend()}
              disabled={busy || !writable || (sendStatus === 'FAILED' && !sendFailure)}
              size="sm"
            >
              <Send className="mr-1 h-4 w-4" />
              登记发送记录
            </Button>
            {sendStatus === 'FAILED' && (
              <p className="text-muted-foreground text-copy-12">
                登记失败**不会**把这封邮件标成「已回复」—— 它仍然是「已收到」，
                失败由发出去的那一行（如实记着 FAILED）与这里的失败原因承担。
              </p>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 附件 */}
      <SectionCard
        title="登记附件元数据"
        description="crm 服务不持有文件字节，这里只记「这个附件是什么」。"
      >
        {!detail ? (
          <EmptyState
            icon={<Paperclip className="h-6 w-6" />}
            title="先选一封邮件"
            description="附件是挂在某一封邮件上的。"
          />
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="文件名">
                <input
                  className={control}
                  value={attachmentName}
                  onChange={(e) => setAttachmentName(e.target.value)}
                  disabled={!writable}
                />
              </Field>
              <Field label="内容类型" hint="例：image/jpeg">
                <input
                  className={control}
                  value={attachmentType}
                  onChange={(e) => setAttachmentType(e.target.value)}
                  disabled={!writable}
                />
              </Field>
              <Field label="字节数" hint="原样登记，页面不做任何算术。">
                <input
                  className={control}
                  value={attachmentSize}
                  onChange={(e) => setAttachmentSize(e.target.value)}
                  disabled={!writable}
                />
              </Field>
            </div>
            <Button
              onClick={() => void submitAttachment()}
              disabled={busy || !writable || !attachmentName}
              size="sm"
              variant="secondary"
            >
              <Paperclip className="mr-1 h-4 w-4" />
              登记附件
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 模板 */}
      <SectionCard
        title="邮件模板"
        description="本模块自己的邮件模板（不是 15.4 知识库，也不是 15.1.1.2 的消息模板）。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="模板编码" hint="业务键，建后不可改。">
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
            <Field label="语言" hint="BCP-47 简写，如 zh-CN / en-US。">
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
          <Field label="正文">
            <textarea
              className={`${control} min-h-24`}
              value={templateBody}
              onChange={(e) => setTemplateBody(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Button
            onClick={() => void submitTemplate()}
            disabled={busy || !writable || !templateCode || !templateName}
            size="sm"
            variant="secondary"
          >
            新建模板
          </Button>

          {(templates?.items.length ?? 0) === 0 ? (
            <EmptyState
              icon={<Mail className="h-6 w-6" />}
              title="还没有邮件模板"
              description="模板用于发送时引用 —— 留空主题与正文时会取模板的同名内容。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {templates?.items.map((template) => (
                <li
                  key={template.id}
                  className="text-copy-13 flex flex-wrap items-center gap-2 py-2"
                >
                  <Badge tone={template.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {templateStatusLabel(template.status)}
                  </Badge>
                  <span className="font-medium">{template.templateCode}</span>
                  <span>{template.name}</span>
                  <span className="text-muted-foreground text-copy-12">
                    {template.locale} · 被引用 {template.usageCount} 次
                  </span>
                  <span className="ml-auto flex gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={busy || !writable}
                      onClick={() => void toggleTemplate(template)}
                    >
                      {template.status === 'ACTIVE' ? '停用' : '启用'}
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={busy || !writable}
                      onClick={() => void removeTemplate(template)}
                    >
                      <Trash2 className="mr-1 h-4 w-4" />
                      删除
                    </Button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 平台同步 */}
      <SectionCard
        title="平台买家邮件同步"
        description="平台适配器未接入 —— 同步会明确失败且不写任何数据，绝不返回空列表冒充「这个店铺没有买家来信」。"
      >
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => void runSync()} size="sm" variant="secondary" disabled={busy}>
              <RefreshCw className="mr-1 h-4 w-4" />
              触发同步
            </Button>
            <span className="text-muted-foreground text-copy-12">
              同步目标店铺取「登记收到的邮件」里选的那一家。
            </span>
          </div>
          <p className="text-muted-foreground text-copy-12">
            {shops
              .filter((shop) => !shop.platformEmailAvailable)
              .map((shop) => `${shop.name}（${shop.platformCode}）：${shop.platformEmailNote}`)
              .join('；') || '所有平台的买家邮件适配器都已接入。'}
          </p>
          {shops.some((shop) => !shop.platformEmailAvailable) && (
            <p className="text-warning text-copy-12 flex items-start gap-1">
              <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
              未接入的平台走人工登记，不要把「同步失败」读成「这个店铺没有买家来信」。
            </p>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
