'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import {
  Archive,
  CheckCheck,
  ClipboardList,
  Inbox,
  MailOpen,
  RefreshCw,
  Search,
  TriangleAlert,
} from 'lucide-react';
import type {
  CrmMessageBatchResult,
  CrmMessageCapabilities,
  CrmMessageDetail,
  CrmMessageOptions,
  CrmMessagePage,
  CrmMessageShopView,
  PlatformMessageAvailability,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  batchActionLabel,
  createMessage,
  directionLabel,
  fetchMessageCapabilities,
  fetchMessageOptions,
  fetchMessagePlatforms,
  fetchMessageShops,
  fetchMessage,
  fetchMessages,
  hasActiveFilter,
  intentStatusLabel,
  intentTypeLabel,
  messageQuery,
  messageSourceLabel,
  messageTypeLabel,
  orderContextText,
  parseAttachmentDraft,
  priorityLabel,
  readStatusLabel,
  runMessageBatch,
  summaryText,
  type MessageParams,
} from './message-inbox-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.1.1.1 消息收件箱工作区。
 *
 * <p>页面按「先看清边界、再登记、再处理」的顺序排，因为蓝图加粗句是
 * 「**消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单」。
 *
 * <ul>
 *   <li><b>能力声明</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       平台消息同步、15.1.1.2 回复、15.1.1.3 分类、11 售后、15.4 工单五块是
 *       available=false，页面把它们<b>显式灰掉</b>并写清归属模块，
 *       不给任何看起来能点的假入口。</li>
 *   <li><b>登记消息</b> —— 买家标识与订单标识<b>必填</b>（后端 DB 层就是 NOT NULL + CHECK），
 *       页面把这一点写在输入框旁边。</li>
 *   <li><b>收件箱</b> —— 按店铺 / 类型 / 优先级 / 未读 / 方向 / 买家 / 订单 / 关键字 /
 *       平台时间窗筛选，真分页；空态区分「筛选无结果」与「收件箱还是空的」。</li>
 *   <li><b>批量处理</b> —— 标记已读 / 未读 / 归档 / 改优先级 / 登记处置意图，
 *       <b>逐条给出结论与原因</b>；失败的条目不会被悄悄跳过。</li>
 *   <li><b>详情</b> —— 消息本体 + 附件 + 关联订单上下文 + 处置意图。
 *       订单上下文查不到时显示「不可用 + 原因」，<b>不显示空白</b>。</li>
 * </ul>
 */
export function MessageInboxWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.message.update');

  const [capabilities, setCapabilities] = useState<CrmMessageCapabilities | null>(null);
  const [shops, setShops] = useState<CrmMessageShopView[]>([]);
  const [options, setOptions] = useState<CrmMessageOptions | null>(null);
  const [platforms, setPlatforms] = useState<PlatformMessageAvailability[]>([]);
  const [page, setPage] = useState<CrmMessagePage | null>(null);
  const [detail, setDetail] = useState<CrmMessageDetail | null>(null);
  const [batch, setBatch] = useState<CrmMessageBatchResult | null>(null);

  // 筛选条件
  const [shopId, setShopId] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [messageType, setMessageType] = useState('');
  const [priority, setPriority] = useState('');
  const [direction, setDirection] = useState('');
  const [readStatus, setReadStatus] = useState('');
  const [buyerReference, setBuyerReference] = useState('');
  const [orderReference, setOrderReference] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 登记表单
  const [draftShopId, setDraftShopId] = useState('');
  const [draftBuyer, setDraftBuyer] = useState('');
  const [draftOrder, setDraftOrder] = useState('');
  const [draftSubject, setDraftSubject] = useState('');
  const [draftBody, setDraftBody] = useState('');
  const [draftDirection, setDraftDirection] = useState('INBOUND');
  const [draftType, setDraftType] = useState('BUYER_MESSAGE');
  const [draftPriority, setDraftPriority] = useState('NORMAL');
  const [draftPlatformTime, setDraftPlatformTime] = useState('');
  const [draftAttachments, setDraftAttachments] = useState('');

  // 批量处理
  const [selected, setSelected] = useState<string[]>([]);
  const [batchAction, setBatchAction] = useState('MARK_READ');
  const [batchPriority, setBatchPriority] = useState('HIGH');
  const [batchIntentType, setBatchIntentType] = useState('AFTERSALE_CASE');
  const [batchReason, setBatchReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const params = useMemo<MessageParams>(
    () => ({
      shopId: shopId || undefined,
      platformCode: platformCode || undefined,
      messageType: messageType || undefined,
      priority: priority || undefined,
      direction: direction || undefined,
      readStatus: readStatus || undefined,
      buyerReference: buyerReference || undefined,
      orderReference: orderReference || undefined,
      keyword: keyword || undefined,
      page: pageIndex,
      size: 20,
    }),
    [
      shopId,
      platformCode,
      messageType,
      priority,
      direction,
      readStatus,
      buyerReference,
      orderReference,
      keyword,
      pageIndex,
    ],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, shopList, opts, platformList, result] = await Promise.all([
        fetchMessageCapabilities(),
        fetchMessageShops(),
        fetchMessageOptions(),
        fetchMessagePlatforms(),
        fetchMessages(params),
      ]);
      setCapabilities(caps);
      setShops(shopList);
      setOptions(opts);
      setPlatforms(platformList);
      setPage(result);
    } catch (e) {
      setError(errorText(e, '消息收件箱加载失败'));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (messageId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchMessage(messageId));
    } catch (e) {
      setActionError(errorText(e, '消息详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitMessage = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const attachments = parseAttachmentDraft(draftAttachments).map((draft) => ({
        fileName: draft.fileName,
        fileUrl: draft.fileUrl,
        contentType: '',
        sizeBytes: null,
        note: '',
      }));
      const created = await createMessage({
        shopId: draftShopId,
        buyerReference: draftBuyer,
        orderReference: draftOrder,
        subject: draftSubject,
        body: draftBody,
        direction: draftDirection,
        messageType: draftType,
        priority: draftPriority,
        platformTime: draftPlatformTime,
        attachments,
        externalMessageId: null,
        note: '',
      });
      setNotice(`消息 ${created.messageNo} 已登记（${created.buyerReference} · ${created.orderReference}）。`);
      setDraftSubject('');
      setDraftBody('');
      setDraftPlatformTime('');
      setDraftAttachments('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记消息失败'));
    } finally {
      setBusy(false);
    }
  }, [
    draftShopId,
    draftBuyer,
    draftOrder,
    draftSubject,
    draftBody,
    draftDirection,
    draftType,
    draftPriority,
    draftPlatformTime,
    draftAttachments,
    load,
  ]);

  /** 单条动作（详情里的标记已读 / 归档 / 登记意图）。 */
  const runSingle = useCallback(
    async (action: string, messageId: string, noticeText: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const result = await runMessageBatch({
          action,
          messageIds: [messageId],
          priority: '',
          intentType: '',
          reason: '',
        });
        const item = result.items[0];
        setNotice(item?.success ? noticeText : `未生效：${item?.reason ?? '未知原因'}`);
        await openDetail(messageId);
        await load();
      } catch (e) {
        setActionError(errorText(e, '操作失败'));
      } finally {
        setBusy(false);
      }
    },
    [openDetail, load],
  );

  const runBatch = useCallback(async () => {
    if (selected.length === 0) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await runMessageBatch({
        action: batchAction,
        messageIds: selected,
        priority: batchAction === 'SET_PRIORITY' ? batchPriority : '',
        intentType:
          batchAction === 'RECORD_INTENT' || batchAction === 'CANCEL_INTENT'
            ? batchIntentType
            : '',
        reason: batchAction === 'RECORD_INTENT' ? batchReason : '',
      });
      setBatch(result);
      setNotice(
        `批量${batchActionLabel(result.action)}完成：共 ${result.requestedCount} 条，` +
          `成功 ${result.succeededCount} 条，失败 ${result.failedCount} 条。`,
      );
      setSelected([]);
      await load();
      if (detail) await openDetail(detail.message.id);
    } catch (e) {
      setActionError(errorText(e, '批量处理失败'));
    } finally {
      setBusy(false);
    }
  }, [selected, batchAction, batchPriority, batchIntentType, batchReason, load, detail, openDetail]);

  if (loading) {
    return (
      <SectionCard title="消息收件箱">
        <p className="text-muted-foreground text-copy-13">加载中…</p>
      </SectionCard>
    );
  }

  const items = page?.items ?? [];
  const filtered = hasActiveFilter(params);
  const syncable = platforms.filter((p) => p.connected);

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {error}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive rounded-md px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 rounded-md px-3 py-2 text-copy-13">{notice}</div>
      )}

      {/* ---------------------------------------------------------- 能力声明 */}
      <SectionCard title="能力声明">
        <ul className="space-y-2 text-copy-13">
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
          <p className="text-muted-foreground mt-3 text-copy-12">{capabilities.highlight}</p>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 平台接入状态 */}
      <SectionCard title="平台消息接入状态">
        {platforms.length === 0 ? (
          <p className="text-muted-foreground text-copy-13">
            没有读到任何店铺 —— 平台清单来自上游 channel 的真实店铺目录，读不到时下面会显示错误，
            而不是一份空清单。
          </p>
        ) : (
          <ul className="space-y-1 text-copy-13">
            {platforms.map((platform) => (
              <li key={platform.platformCode} className="flex flex-wrap items-start gap-2">
                <Badge tone={platform.connected ? 'success' : 'warning'}>
                  {platform.connected ? '已接入' : '未接入'}
                </Badge>
                <span className="font-medium">{platform.platformCode}</span>
                <span className="text-muted-foreground">{platform.reason}</span>
              </li>
            ))}
          </ul>
        )}
        <p className="text-muted-foreground mt-3 text-copy-12">
          平台侧买家站内信适配器未接入 ⇒ 同步入口会返回 <strong>409</strong> 且一条数据都不写；
          消息只能<strong>人工登记</strong>。本页<strong>不提供</strong>「同步平台消息」按钮 ——
          一个点了就成功的假按钮会让运营以为平台消息已经进来了。
          {syncable.length === 0 && '（当前没有任何平台显示为已接入。）'}
        </p>
      </SectionCard>

      {/* ---------------------------------------------------------- 登记消息 */}
      <SectionCard title="登记平台消息 / 买家消息">
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="店铺" hint="来自上游 channel 的真实店铺清单。">
              <select
                className={control}
                value={draftShopId}
                onChange={(e) => setDraftShopId(e.target.value)}
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
            <Field
              label="买家标识（必填）"
              hint="蓝图要求消息必须关联到买家与订单；本仓没有买家档案，这里填平台买家号。"
            >
              <input
                className={control}
                value={draftBuyer}
                onChange={(e) => setDraftBuyer(e.target.value)}
                placeholder="AMAZON-BUYER-1"
                disabled={!writable}
              />
            </Field>
            <Field
              label="订单标识（必填）"
              hint="留空的订单在数据库层就存不进去 —— 客服看到消息还要去猜是哪一单。"
            >
              <input
                className={control}
                value={draftOrder}
                onChange={(e) => setDraftOrder(e.target.value)}
                placeholder="SO-1001"
                disabled={!writable}
              />
            </Field>
            <Field label="主题">
              <input
                className={control}
                value={draftSubject}
                onChange={(e) => setDraftSubject(e.target.value)}
                placeholder="买家问什么时候发货"
                disabled={!writable}
              />
            </Field>
            <Field label="收发方向">
              <select
                className={control}
                value={draftDirection}
                onChange={(e) => setDraftDirection(e.target.value)}
                disabled={!writable}
              >
                <option value="INBOUND">收到（平台站内信 / 买家消息）</option>
                <option value="OUTBOUND">发出</option>
              </select>
            </Field>
            <Field label="消息类型">
              <select
                className={control}
                value={draftType}
                onChange={(e) => setDraftType(e.target.value)}
                disabled={!writable}
              >
                <option value="BUYER_MESSAGE">买家消息</option>
                <option value="PLATFORM_INBOX">平台站内信</option>
                <option value="SYSTEM_NOTICE">平台通知</option>
                <option value="COMPLIANCE">合规通知</option>
              </select>
            </Field>
            <Field label="优先级">
              <select
                className={control}
                value={draftPriority}
                onChange={(e) => setDraftPriority(e.target.value)}
                disabled={!writable}
              >
                <option value="LOW">低</option>
                <option value="NORMAL">普通</option>
                <option value="HIGH">高</option>
                <option value="URGENT">紧急</option>
              </select>
            </Field>
            <Field
              label="平台时间（必填）"
              hint="带时区偏移量的时刻，例如 2026-06-01T09:30:00+08:00。缺偏移量的时间会在东八区静默偏 8 小时。"
            >
              <input
                className={control}
                value={draftPlatformTime}
                onChange={(e) => setDraftPlatformTime(e.target.value)}
                placeholder="2026-06-01T09:30:00+08:00"
                disabled={!writable}
              />
            </Field>
          </div>
          <Field label="正文">
            <textarea
              className={control}
              rows={4}
              value={draftBody}
              onChange={(e) => setDraftBody(e.target.value)}
              disabled={!writable}
            />
          </Field>
          <Field
            label="附件"
            hint="一行一个附件：文件名，或「文件名 | 链接」。附件数由数据库维护，列表上的「带附件」徽标与它一致。"
          >
            <textarea
              className={control}
              rows={3}
              value={draftAttachments}
              onChange={(e) => setDraftAttachments(e.target.value)}
              placeholder={'photo.png | https://cdn/photo.png\ninvoice.pdf'}
              disabled={!writable}
            />
          </Field>
          <Button
            onClick={() => void submitMessage()}
            disabled={busy || !writable || !draftShopId || !draftBuyer || !draftOrder || !draftPlatformTime}
            size="sm"
          >
            <Inbox className="mr-1 h-4 w-4" />
            登记消息
          </Button>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 收件箱 */}
      <SectionCard
        title="收件箱"
        description={summaryText(page?.summary)}
        extra={
          <Button onClick={() => void load()} size="sm" variant="secondary">
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <select
            className={control}
            value={shopId}
            onChange={(e) => {
              setShopId(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部店铺</option>
            {shops.map((shop) => (
              <option key={shop.id} value={shop.id}>
                {shop.name} · {shop.platformCode}
              </option>
            ))}
          </select>
          <select
            className={control}
            value={messageType}
            onChange={(e) => {
              setMessageType(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部类型</option>
            {(options?.messageTypes ?? []).map((type) => (
              <option key={type} value={type}>
                {messageTypeLabel(type)}
              </option>
            ))}
          </select>
          <select
            className={control}
            value={priority}
            onChange={(e) => {
              setPriority(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部优先级</option>
            {(options?.priorities ?? []).map((item) => (
              <option key={item} value={item}>
                {priorityLabel(item)}
              </option>
            ))}
          </select>
          <select
            className={control}
            value={readStatus}
            onChange={(e) => {
              setReadStatus(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部状态</option>
            <option value="UNREAD">未读</option>
            <option value="READ">已读</option>
            <option value="ARCHIVED">已归档</option>
          </select>
          <select
            className={control}
            value={direction}
            onChange={(e) => {
              setDirection(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部方向</option>
            {(options?.directions ?? []).map((item) => (
              <option key={item} value={item}>
                {directionLabel(item)}
              </option>
            ))}
          </select>
          <select
            className={control}
            value={platformCode}
            onChange={(e) => {
              setPlatformCode(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部平台</option>
            {(options?.platformCodes ?? []).map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
          <input
            className={control}
            value={buyerReference}
            onChange={(e) => {
              setBuyerReference(e.target.value);
              setPageIndex(0);
            }}
            placeholder="按买家标识筛选"
          />
          <input
            className={control}
            value={orderReference}
            onChange={(e) => {
              setOrderReference(e.target.value);
              setPageIndex(0);
            }}
            placeholder="按订单标识筛选"
          />
          <input
            className={control}
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageIndex(0);
            }}
            placeholder="关键字（主题 / 正文 / 买家 / 订单）"
          />
        </div>

        {items.length === 0 ? (
          <EmptyState
            icon={filtered ? <Search className="h-7 w-7" /> : <Inbox className="h-7 w-7" />}
            title={filtered ? '当前筛选条件下没有消息' : '收件箱还是空的'}
            description={
              filtered
                ? '把筛选条件放宽一点再试 —— 这不代表收件箱里没有消息。'
                : '平台侧买家站内信适配器未接入，平台消息同步不到本服务；' +
                  '消息可以先从上面的「登记平台消息 / 买家消息」人工录入。'
            }
          />
        ) : (
          <>
            <ul className="space-y-2 text-copy-13">
              {items.map((row) => (
                <li key={row.id} className="border-border rounded-md px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      type="checkbox"
                      aria-label={`选择消息 ${row.messageNo}`}
                      checked={selected.includes(row.id)}
                      onChange={(e) =>
                        setSelected((current) =>
                          e.target.checked
                            ? [...current, row.id]
                            : current.filter((id) => id !== row.id),
                        )
                      }
                    />
                    <Badge tone={row.readStatus === 'UNREAD' ? 'info' : 'neutral'}>
                      {readStatusLabel(row.readStatus)}
                    </Badge>
                    <Badge
                      tone={
                        row.priority === 'URGENT'
                          ? 'danger'
                          : row.priority === 'HIGH'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {priorityLabel(row.priority)}
                    </Badge>
                    <button
                      type="button"
                      className="hover:underline font-medium"
                      onClick={() => void openDetail(row.id)}
                    >
                      {row.subject || row.messageNo}
                    </button>
                    <span>
                      {row.shopName} · {row.buyerReference} · {row.orderReference}
                    </span>
                    <span className="text-muted-foreground">
                      {messageTypeLabel(row.messageType)} · {directionLabel(row.direction)} · 平台时间{' '}
                      {row.platformTime}
                    </span>
                    {row.attachmentCount > 0 && <Badge tone="brand">带附件 {row.attachmentCount}</Badge>}
                    <span className="text-muted-foreground">{messageSourceLabel(row.source)}</span>
                  </div>
                </li>
              ))}
            </ul>

            <div className="mt-3 flex flex-wrap items-center gap-2 text-copy-12">
              <span className="text-muted-foreground">
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
          </>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 批量处理 */}
      <SectionCard title="批量处理">
        <div className="space-y-3">
          <p className="text-muted-foreground text-copy-13">
            已选 <strong>{selected.length}</strong> 条。批量结果<strong>逐条</strong>给出结论与原因 ——
            「点了 20 条、实际生效 17 条」这件事必须看得见，失败的条目不会被悄悄跳过。
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="动作">
              <select
                className={control}
                value={batchAction}
                onChange={(e) => setBatchAction(e.target.value)}
              >
                <option value="MARK_READ">标记已读</option>
                <option value="MARK_UNREAD">标记未读</option>
                <option value="ARCHIVE">归档</option>
                <option value="SET_PRIORITY">改优先级</option>
                <option value="RECORD_INTENT">登记处置意图</option>
                <option value="CANCEL_INTENT">撤销处置意图</option>
              </select>
            </Field>
            {batchAction === 'SET_PRIORITY' && (
              <Field label="目标优先级">
                <select
                  className={control}
                  value={batchPriority}
                  onChange={(e) => setBatchPriority(e.target.value)}
                >
                  <option value="LOW">低</option>
                  <option value="NORMAL">普通</option>
                  <option value="HIGH">高</option>
                  <option value="URGENT">紧急</option>
                </select>
              </Field>
            )}
            {(batchAction === 'RECORD_INTENT' || batchAction === 'CANCEL_INTENT') && (
              <Field
                label="意图类型"
                hint="下游未交付：只在本服务留意图，不建售后单、不建工单、不编下游单号。"
              >
                <select
                  className={control}
                  value={batchIntentType}
                  onChange={(e) => setBatchIntentType(e.target.value)}
                >
                  <option value="AFTERSALE_CASE">转售后（11 售后未交付）</option>
                  <option value="SERVICE_TICKET">建工单（15.4 工单未交付）</option>
                </select>
              </Field>
            )}
            {batchAction === 'RECORD_INTENT' && (
              <Field label="原因（必填）" hint="没有理由的意图没法复核。">
                <input
                  className={control}
                  value={batchReason}
                  onChange={(e) => setBatchReason(e.target.value)}
                  placeholder="买家要求退货，已提交售后申请"
                />
              </Field>
            )}
            <Button
              onClick={() => void runBatch()}
              disabled={busy || !writable || selected.length === 0}
              size="sm"
            >
              <CheckCheck className="mr-1 h-4 w-4" />
              执行{batchActionLabel(batchAction)}
            </Button>
          </div>

          {batch && (
            <div className="space-y-2 text-copy-12">
              <p className="text-muted-foreground">{batch.boundaryNote}</p>
              <ul className="max-h-64 space-y-1 overflow-auto">
                {batch.items.map((item) => (
                  <li key={item.messageId} className="flex flex-wrap gap-2">
                    <Badge tone={item.success ? 'success' : 'danger'}>
                      {item.success ? '成功' : '未生效'}
                    </Badge>
                    <span>{item.messageNo || item.messageId}</span>
                    {item.reason && <span className="text-muted-foreground">{item.reason}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 详情 */}
      {detail && (
        <SectionCard title={`消息详情 · ${detail.message.messageNo}`}>
          <div className="space-y-3 text-copy-13">
            <p className="text-muted-foreground">
              {detail.message.shopName} · {detail.message.platformCode} · 买家{' '}
              <strong>{detail.message.buyerReference}</strong> · 订单{' '}
              <strong>{detail.message.orderReference}</strong> · 平台时间{' '}
              {detail.message.platformTime} · {messageSourceLabel(detail.message.source)}
            </p>
            {detail.message.subject && <p className="font-medium">{detail.message.subject}</p>}
            {detail.message.body && <p className="whitespace-pre-wrap">{detail.message.body}</p>}

            <div>
              <h3 className="font-medium">附件</h3>
              {detail.attachments.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">这条消息没有附件。</p>
              ) : (
                <ul className="space-y-1 text-copy-12">
                  {detail.attachments.map((attachment) => (
                    <li key={attachment.id}>
                      {attachment.fileName}
                      {attachment.fileUrl && (
                        <a className="hover:underline ml-2" href={attachment.fileUrl}>
                          打开附件
                        </a>
                      )}
                      {attachment.contentType && ` · ${attachment.contentType}`}
                      {attachment.sizeBytes !== null && ` · ${attachment.sizeBytes} 字节`}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h3 className="font-medium">关联订单上下文</h3>
              {detail.orderContext.available ? (
                <p>{orderContextText(detail.orderContext)}</p>
              ) : (
                <p className="text-destructive">
                  <TriangleAlert className="mr-1 inline h-4 w-4" />
                  {orderContextText(detail.orderContext)}
                </p>
              )}
              <p className="text-muted-foreground mt-1 text-copy-12">
                订单上下文的真值在 trade 服务（9.1.1.1），本服务只经授权 HTTP API 只读补全；
                查不到时显示的是「不可用 + 原因」，不是空白。
              </p>
            </div>

            <div>
              <h3 className="font-medium">处置意图</h3>
              {detail.intents.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">
                  还没有处置意图。转售后（11 售后）与建工单（15.4 工单）的下游 API
                  在当前版本里不可调用，批量动作只在本服务留一条意图记录。
                </p>
              ) : (
                <ul className="space-y-1 text-copy-12">
                  {detail.intents.map((intent) => (
                    <li key={intent.id} className="flex flex-wrap gap-2">
                      <Badge tone={intent.status === 'RECORDED' ? 'info' : 'neutral'}>
                        {intentStatusLabel(intent.status)}
                      </Badge>
                      <span>{intentTypeLabel(intent.intentType)}</span>
                      <span className="text-muted-foreground">{intent.reason}</span>
                      <span className="text-muted-foreground">{intent.createdAt}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={busy || !writable}
                onClick={() =>
                  void runSingle(
                    detail.message.readStatus === 'UNREAD' ? 'MARK_READ' : 'MARK_UNREAD',
                    detail.message.id,
                    '已更新这条消息的已读状态。',
                  )
                }
              >
                <MailOpen className="mr-1 h-4 w-4" />
                {detail.message.readStatus === 'UNREAD' ? '标记已读' : '标记未读'}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={busy || !writable}
                onClick={() => void runSingle('ARCHIVE', detail.message.id, '已归档这条消息。')}
              >
                <Archive className="mr-1 h-4 w-4" />
                归档
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={busy || !writable}
                onClick={() => {
                  setBatchAction('RECORD_INTENT');
                  setSelected([detail.message.id]);
                  setNotice('已把这条消息放进批量处理的选择里：在「批量处理」里填原因后执行「登记处置意图」。');
                }}
              >
                <ClipboardList className="mr-1 h-4 w-4" />
                登记处置意图
              </Button>
            </div>
          </div>
        </SectionCard>
      )}
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
