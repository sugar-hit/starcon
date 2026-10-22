'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  ClipboardList,
  Inbox,
  MessageSquare,
  RefreshCw,
  Send,
  ShoppingCart,
  Trash2,
} from 'lucide-react';
import type {
  CrmCartAbandonmentDetail,
  CrmCartAbandonmentPage,
  CrmCartCapabilities,
  CrmCartMessagePage,
  CrmCartMessageRow,
  CrmCartPolicy,
  CrmCartRecoveryRate,
  CrmCartShopView,
  CrmCartUnsubscribePage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  approveCartMessage,
  cartAbandonmentQuery,
  cartAbandonmentStatusLabel,
  cartAmountText,
  cartChannelLabel,
  cartMessageStatusLabel,
  cartPlatformTypeLabel,
  cartRecoveryRateText,
  cartRecoveryStateLabel,
  cartStageLabel,
  cartTouchStatusLabel,
  cartTransportLabel,
  confirmCartTouch,
  createCartAbandonment,
  createCartMessage,
  createCartUnsubscribe,
  declareCartShopType,
  deleteCartUnsubscribe,
  disableCartMessage,
  enableCartMessage,
  fetchCartAbandonment,
  fetchCartAbandonments,
  fetchCartCapabilities,
  fetchCartMessages,
  fetchCartPolicy,
  fetchCartRecoveryRate,
  fetchCartShops,
  fetchCartUnsubscribes,
  registerCartRecovery,
  registerCartTouch,
  syncCartEvents,
  updateCartMessage,
  updateCartPolicy,
} from './cart-recovery-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.6.2.2 购物车与弃单挽回工作区。
 *
 * <p>页面按「先看清边界、再看漏斗、最后才动手」的顺序排，因为蓝图加粗句是
 * 「**此项是独立站(DTC)特有能力**，平台订单无购物车概念；触达须遵守退订与频次上限，
 * 挽回话术需人工确认后启用」—— 界面上第一件要让人看到的就是**能力边界**，
 * 而不是一张可以直接点「批量挽回」的按钮。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」；
 *       平台购物车事件、触达通道、9 订单关联、13 分析四块是 available=false，
 *       页面把它们**显式灰掉**并写清归属模块。</li>
 *   <li><b>挽回率报表</b> —— **服务端真算**，页面原样展示，<b>不做任何前端算术</b>。</li>
 *   <li><b>弃单清单</b> —— 真分页 + 真筛选；空态区分「筛选无结果」与「还没有弃单」。</li>
 *   <li><b>弃单详情</b> —— 触达时间线 + 登记触达 + 回填结果 + 登记成交。</li>
 *   <li><b>挽回话术</b> —— 草稿 → 已审核 → 已启用 → 已停用；**只有已启用的能选</b>。</li>
 *   <li><b>退订名单</b> —— 登记与取消；触达前服务端会硬拦，这里只是让人看得见。</li>
 *   <li><b>频次策略</b> —— 每日 / 每周期上限与弃单超时，可改、有天花板。</li>
 * </ul>
 */
export function CartRecoveryWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.cart.update');

  const [capabilities, setCapabilities] = useState<CrmCartCapabilities | null>(null);
  const [shops, setShops] = useState<CrmCartShopView[]>([]);
  const [page, setPage] = useState<CrmCartAbandonmentPage | null>(null);
  const [detail, setDetail] = useState<CrmCartAbandonmentDetail | null>(null);
  const [messages, setMessages] = useState<CrmCartMessagePage | null>(null);
  const [unsubscribes, setUnsubscribes] = useState<CrmCartUnsubscribePage | null>(null);
  const [policy, setPolicy] = useState<CrmCartPolicy | null>(null);
  const [rate, setRate] = useState<CrmCartRecoveryRate | null>(null);

  // 筛选
  const [shopId, setShopId] = useState('');
  const [status, setStatus] = useState('');
  const [stage, setStage] = useState('');
  const [buyerRef, setBuyerRef] = useState('');
  const [cartRef, setCartRef] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  // 登记弃单
  const [newShopId, setNewShopId] = useState('');
  const [newCartRef, setNewCartRef] = useState('');
  const [newStage, setNewStage] = useState('CART');
  const [newBuyerRef, setNewBuyerRef] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newPhone, setNewPhone] = useState('');
  const [newCurrency, setNewCurrency] = useState('USD');
  const [newAmount, setNewAmount] = useState('');
  const [newItemCount, setNewItemCount] = useState('');
  const [newSummary, setNewSummary] = useState('');
  const [newAbandonedAt, setNewAbandonedAt] = useState('');

  // 触达
  const [touchMessageId, setTouchMessageId] = useState('');
  const [touchResultStatus, setTouchResultStatus] = useState('SENT');
  const [touchFailure, setTouchFailure] = useState('');
  const [touchSentAt, setTouchSentAt] = useState('');

  // 成交
  const [recoveryOrderNo, setRecoveryOrderNo] = useState('');
  const [recoveryAmount, setRecoveryAmount] = useState('');
  const [recoveryAt, setRecoveryAt] = useState('');

  // 话术
  const [messageCode, setMessageCode] = useState('');
  const [messageName, setMessageName] = useState('');
  const [messageChannel, setMessageChannel] = useState('EMAIL');
  const [messageSubject, setMessageSubject] = useState('');
  const [messageBody, setMessageBody] = useState('');

  // 退订
  const [unsubType, setUnsubType] = useState('EMAIL');
  const [unsubValue, setUnsubValue] = useState('');
  const [unsubReason, setUnsubReason] = useState('');

  // 频次策略
  const [perDay, setPerDay] = useState('');
  const [perCycle, setPerCycle] = useState('');
  const [cycleDays, setCycleDays] = useState('');
  const [timeoutHours, setTimeoutHours] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  /** 当前是否有筛选条件 —— 空态要区分「筛选无结果」与「还没有弃单」。 */
  const hasFilter = useMemo(
    () => Boolean(shopId || status || stage || buyerRef || cartRef || keyword),
    [shopId, status, stage, buyerRef, cartRef, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, shopList, cartPage, messagePage, unsubPage, currentPolicy, currentRate] =
        await Promise.all([
          fetchCartCapabilities(),
          fetchCartShops(),
          fetchCartAbandonments({
            shopId: shopId || undefined,
            status: status || undefined,
            stage: stage || undefined,
            buyerRef: buyerRef || undefined,
            cartRef: cartRef || undefined,
            keyword: keyword || undefined,
            page: pageIndex,
            size: 20,
          }),
          fetchCartMessages({ size: 50 }),
          fetchCartUnsubscribes({ size: 20 }),
          fetchCartPolicy(),
          fetchCartRecoveryRate({ shopId: shopId || undefined }),
        ]);
      setCapabilities(caps);
      setShops(shopList);
      setPage(cartPage);
      setMessages(messagePage);
      setUnsubscribes(unsubPage);
      setPolicy(currentPolicy);
      setRate(currentRate);
      setPerDay(String(currentPolicy.maxTouchesPerDay));
      setPerCycle(String(currentPolicy.maxTouchesPerCycle));
      setCycleDays(String(currentPolicy.cycleDays));
      setTimeoutHours(String(currentPolicy.abandonTimeoutHours));
    } catch (e) {
      setError(errorText(e, '弃单挽回工作区加载失败'));
    } finally {
      setLoading(false);
    }
  }, [shopId, status, stage, buyerRef, cartRef, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (cartId: string) => {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchCartAbandonment(cartId));
    } catch (e) {
      setActionError(errorText(e, '弃单详情读取失败'));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitAbandonment = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createCartAbandonment({
        shopId: newShopId,
        cartRef: newCartRef,
        externalEventId: '',
        stage: newStage,
        buyerRef: newBuyerRef,
        buyerEmail: newEmail,
        buyerPhone: newPhone,
        currency: newCurrency,
        // ⚠️ 只做**格式转换**（输入串 → 契约里的 number），**不做任何前端算术**：
        //   金额与数量由后端按 numeric(19,6) 落库，页面不参与计算。
        amount: newAmount === '' ? 0 : Number(newAmount),
        itemCount: newItemCount === '' ? 0 : Number(newItemCount),
        itemSummary: newSummary,
        abandonedAt: newAbandonedAt,
        note: '',
      });
      setNotice(`已登记弃单 ${created.cartNo}（挽回时点 ${created.recoverDeadlineAt}）。`);
      setNewCartRef('');
      setNewBuyerRef('');
      setNewSummary('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记弃单失败'));
    } finally {
      setBusy(false);
    }
  }, [
    newShopId,
    newCartRef,
    newStage,
    newBuyerRef,
    newEmail,
    newPhone,
    newCurrency,
    newAmount,
    newItemCount,
    newSummary,
    newAbandonedAt,
    load,
  ]);

  const submitTouch = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await registerCartTouch(detail.abandonment.id, {
        messageId: touchMessageId,
        note: '',
      });
      setNotice(result.note);
      setTouchMessageId('');
      await openDetail(detail.abandonment.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记触达失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, touchMessageId, openDetail, load]);

  const submitTouchResult = useCallback(
    async (touchId: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const updated = await confirmCartTouch(touchId, {
          status: touchResultStatus,
          failureReason: touchFailure,
          sentAt: touchSentAt || null,
          note: '',
        });
        setNotice(`触达 ${updated.id} 的结果已回填为「${cartTouchStatusLabel(updated.status)}」。`);
        setTouchFailure('');
        if (detail) await openDetail(detail.abandonment.id);
        await load();
      } catch (e) {
        setActionError(errorText(e, '回填触达结果失败'));
      } finally {
        setBusy(false);
      }
    },
    [touchResultStatus, touchFailure, touchSentAt, detail, openDetail, load],
  );

  const submitRecovery = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const updated = await registerCartRecovery(detail.abandonment.id, {
        salesOrderNo: recoveryOrderNo,
        recoveredAmount: recoveryAmount === '' ? 0 : Number(recoveryAmount),
        recoveredAt: recoveryAt,
        note: '',
      });
      setNotice(`已登记挽回成交（订单号 ${updated.recoveredOrderNo}）。`);
      setRecoveryOrderNo('');
      await openDetail(detail.abandonment.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记挽回成交失败'));
    } finally {
      setBusy(false);
    }
  }, [detail, recoveryOrderNo, recoveryAmount, recoveryAt, openDetail, load]);

  const submitMessage = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const created = await createCartMessage({
        messageCode: messageCode,
        name: messageName,
        channel: messageChannel,
        locale: 'zh-CN',
        subject: messageSubject,
        body: messageBody,
        note: '',
      });
      setNotice(
        `已创建话术 ${created.messageCode}，当前是「${cartMessageStatusLabel(created.status)}」—— 需人工审核后启用才能被触达选用。`,
      );
      setMessageCode('');
      setMessageName('');
      setMessageSubject('');
      setMessageBody('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '创建话术失败'));
    } finally {
      setBusy(false);
    }
  }, [messageCode, messageName, messageChannel, messageSubject, messageBody, load]);

  const submitMessageUpdate = useCallback(
    async (row: CrmCartMessageRow) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const updated = await updateCartMessage(row.id, {
          messageCode: row.messageCode,
          name: row.name,
          channel: row.channel,
          locale: row.locale,
          subject: row.subject,
          body: row.body,
          note: row.note,
        });
        setNotice(
          `话术 ${updated.messageCode} 已保存（版本 ${updated.version}，${cartMessageStatusLabel(updated.status)}）。`,
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '改话术失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitMessageAction = useCallback(
    async (row: CrmCartMessageRow, action: 'approve' | 'enable' | 'disable') => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const updated =
          action === 'approve'
            ? await approveCartMessage(row.id)
            : action === 'enable'
              ? await enableCartMessage(row.id)
              : await disableCartMessage(row.id);
        setNotice(`话术 ${updated.messageCode} 现在是「${cartMessageStatusLabel(updated.status)}」。`);
        await load();
      } catch (e) {
        setActionError(errorText(e, '话术状态流转失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitUnsubscribe = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      await createCartUnsubscribe({
        contactType: unsubType,
        contactValue: unsubValue,
        shopId: null,
        reason: unsubReason,
        note: '',
      });
      setNotice('已登记退订：这个联系人之后一律不触达（服务端在发出去之前会硬拦）。');
      setUnsubValue('');
      setUnsubReason('');
      await load();
    } catch (e) {
      setActionError(errorText(e, '登记退订失败'));
    } finally {
      setBusy(false);
    }
  }, [unsubType, unsubValue, unsubReason, load]);

  const removeUnsubscribe = useCallback(
    async (id: string) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        await deleteCartUnsubscribe(id);
        setNotice('已取消退订。');
        await load();
      } catch (e) {
        setActionError(errorText(e, '取消退订失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitPolicy = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const updated = await updateCartPolicy({
        maxTouchesPerDay: Number(perDay),
        maxTouchesPerCycle: Number(perCycle),
        cycleDays: Number(cycleDays),
        abandonTimeoutHours: Number(timeoutHours),
        note: '',
      });
      setNotice(
        `频次策略已更新：每日 ${updated.maxTouchesPerDay} 次 / 每 ${updated.cycleDays} 天 ${updated.maxTouchesPerCycle} 次 / 弃单超时 ${updated.abandonTimeoutHours} 小时。`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '更新频次策略失败'));
    } finally {
      setBusy(false);
    }
  }, [perDay, perCycle, cycleDays, timeoutHours, load]);

  const runSync = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    setNotice('');
    try {
      const result = await syncCartEvents({ shopId: newShopId || shops[0]?.id || '' });
      setNotice(`已同步 ${result.synced} 条购物车 / 结账事件。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '平台购物车事件同步未接入'));
    } finally {
      setBusy(false);
    }
  }, [newShopId, shops, load]);

  const declareShop = useCallback(
    async (shop: CrmCartShopView) => {
      setBusy(true);
      setActionError(null);
      setNotice('');
      try {
        const updated = await declareCartShopType(shop.id, { platformType: 'DTC', note: '' });
        setNotice(
          `店铺 ${updated.name} 的平台类型现在是「${cartPlatformTypeLabel(updated.platformType)}」。`,
        );
        await load();
      } catch (e) {
        setActionError(errorText(e, '声明店铺平台类型失败'));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  /** 只有**已启用**的话术能被触达流程选用（服务端还会再挡一道）。 */
  const enabledMessages = useMemo(
    () => (messages?.items ?? []).filter((row) => row.status === 'ENABLED'),
    [messages],
  );

  if (loading && !page) {
    return (
      <SectionCard title="购物车与弃单挽回">
        <p className="text-muted-foreground text-copy-13">正在读取弃单清单……</p>
      </SectionCard>
    );
  }

  if (error && !page) {
    return (
      <SectionCard title="购物车与弃单挽回">
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
        {capabilities?.platformTypeRule && (
          <p className="text-muted-foreground text-copy-12 mt-3">
            平台类型口径：{capabilities.platformTypeRule}
          </p>
        )}
        {capabilities?.messageLifecycle && (
          <p className="text-muted-foreground text-copy-12 mt-1">
            话术生命周期：{capabilities.messageLifecycle}
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

      {/* ---------------------------------------------------------- 挽回率报表 */}
      <SectionCard
        title="挽回率报表"
        description="服务端真算，页面原样展示（不做任何前端算术）。"
        extra={
          <Button onClick={() => void load()} size="sm" variant="secondary" disabled={busy}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        {!rate || rate.totalAbandonments === 0 ? (
          <EmptyState
            icon={<ClipboardList className="h-6 w-6" />}
            title="还没有可统计的弃单"
            description="挽回率的分母是弃单总数；一条弃单都没有时不显示比率（不是 0%）。"
          />
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-4">
              <Stat label="弃单总数" value={String(rate.totalAbandonments)} />
              <Stat label="已挽回" value={String(rate.recoveredCount)} />
              <Stat label="挽回率" value={cartRecoveryRateText(rate.recoveryRate)} />
              <Stat label="触达记录" value={String(rate.touchCount)} />
            </div>
            <p className="text-muted-foreground text-copy-12">
              弃单金额 {cartAmountText(rate.totalAmount)} · 已挽回金额{' '}
              {cartAmountText(rate.recoveredAmount)} · 待挽回 {rate.pendingCount} · 已过期{' '}
              {rate.expiredCount} · 退订名单 {rate.unsubscribedCount} 条
            </p>
            {rate.byShop.length > 0 && (
              <div>
                <p className="text-copy-13 font-medium">按店铺</p>
                <ul className="divide-border divide-y">
                  {rate.byShop.map((bucket) => (
                    <li key={bucket.shopId} className="text-copy-12 flex flex-wrap gap-3 py-1">
                      <span className="font-medium">{bucket.shopName}</span>
                      <span className="text-muted-foreground">
                        弃单 {bucket.total} · 已挽回 {bucket.recovered} · 挽回率{' '}
                        {cartRecoveryRateText(bucket.recoveryRate)}
                      </span>
                      <span className="text-muted-foreground">
                        金额 {cartAmountText(bucket.totalAmount)} → 已挽回{' '}
                        {cartAmountText(bucket.recoveredAmount)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {rate.byChannel.length > 0 && (
              <p className="text-muted-foreground text-copy-12">
                触达量：{rate.byChannel.map((c) => `${cartChannelLabel(c.channel)} ${c.touchCount}`).join(' · ')}
              </p>
            )}
            <p className="text-muted-foreground text-copy-12">{rate.windowNote}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 弃单清单 */}
      <SectionCard
        title="弃单清单"
        description="独立站购物车 / 结账未完成。平台店铺的购物车事件在登记入口就被拒了。"
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
                    {shop.name} · {shop.platformCode} ·{' '}
                    {cartPlatformTypeLabel(shop.platformType)}
                  </option>
                ))}
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
                <option value="ABANDONED">待挽回</option>
                <option value="RECOVERED">已挽回成交</option>
                <option value="EXPIRED">已过期（未挽回）</option>
              </select>
            </Field>
            <Field label="阶段">
              <select
                className={control}
                value={stage}
                onChange={(e) => {
                  setStage(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">（全部阶段）</option>
                <option value="CART">只加购</option>
                <option value="CHECKOUT">进了结账</option>
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
                placeholder="例：SHOP-BUYER-8821"
              />
            </Field>
            <Field label="购物车号">
              <input
                className={control}
                value={cartRef}
                onChange={(e) => {
                  setCartRef(e.target.value);
                  setPageIndex(0);
                }}
                placeholder="例：cart-10021"
              />
            </Field>
            <Field label="商品摘要包含">
              <input
                className={control}
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
                placeholder="例：雨伞"
              />
            </Field>
          </div>

          {(page?.items.length ?? 0) === 0 ? (
            hasFilter ? (
              <EmptyState
                icon={<ShoppingCart className="h-6 w-6" />}
                title="当前筛选没有匹配的弃单"
                description="把筛选条件放宽一点再试 —— 「筛选无结果」和「还没有弃单」是两件不同的事。"
              />
            ) : (
              <EmptyState
                icon={<ShoppingCart className="h-6 w-6" />}
                title="还没有弃单"
                description="平台购物车事件适配器未交付，弃单只能人工登记（见下方「登记弃单」）。平台店铺的购物车事件不适用此项。"
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
                      <Badge tone={row.platformType === 'DTC' ? 'success' : 'warning'}>
                        {cartPlatformTypeLabel(row.platformType)}
                      </Badge>
                      <Badge
                        tone={
                          row.status === 'RECOVERED'
                            ? 'success'
                            : row.status === 'EXPIRED'
                              ? 'danger'
                              : 'neutral'
                        }
                      >
                        {cartAbandonmentStatusLabel(row.status)}
                      </Badge>
                      <span className="font-medium">{row.itemSummary || '（无摘要）'}</span>
                      <span className="text-muted-foreground text-copy-12">
                        {row.cartNo} · {row.shopName} · {row.buyerRef}
                      </span>
                    </div>
                    <div className="text-muted-foreground text-copy-12 mt-1">
                      {cartStageLabel(row.stage)} · {row.abandonedAt} · 挽回时点{' '}
                      {row.recoverDeadlineAt} · 金额 {cartAmountText(row.amount, row.currency)} · 件数{' '}
                      {row.itemCount} · {cartRecoveryStateLabel(row)}
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {page && page.totalElements > 0 && (
            <div className="text-copy-12 flex items-center justify-between">
              <span className="text-muted-foreground">
                共 {page.totalElements} 条 · 第 {page.page + 1} / {Math.max(page.totalPages, 1)} 页
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

      {/* ---------------------------------------------------------- 弃单详情 */}
      {detail && (
        <SectionCard
          title={`弃单 ${detail.abandonment.cartNo}`}
          description={`${detail.abandonment.shopName} · ${cartStageLabel(detail.abandonment.stage)} · ${cartRecoveryStateLabel(detail.abandonment)}`}
        >
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-12">
              买家 {detail.abandonment.buyerRef} · 邮箱 {detail.abandonment.buyerEmail || '（无）'} ·
              手机 {detail.abandonment.buyerPhone || '（无）'} · 金额{' '}
              {cartAmountText(detail.abandonment.amount, detail.abandonment.currency)} · 件数{' '}
              {detail.abandonment.itemCount}
            </p>
            <p className="text-muted-foreground text-copy-12">
              事件时刻 {detail.abandonment.abandonedAt} · 挽回时点{' '}
              {detail.abandonment.recoverDeadlineAt} · 来源 {detail.abandonment.source}
              {detail.abandonment.recoveredOrderNo
                ? ` · 成交订单号 ${detail.abandonment.recoveredOrderNo}`
                : ''}
            </p>

            <div>
              <p className="text-copy-13 font-medium">触达记录</p>
              {detail.touches.length === 0 ? (
                <p className="text-muted-foreground text-copy-12 mt-1">
                  还没有触达。触达要过了挽回时点才能登记。
                </p>
              ) : (
                <ul className="divide-border divide-y">
                  {detail.touches.map((touch) => (
                    <li key={touch.id} className="py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge
                          tone={
                            touch.status === 'SENT'
                              ? 'success'
                              : touch.status === 'FAILED'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {cartTouchStatusLabel(touch.status)}
                        </Badge>
                        <span className="font-medium">
                          {cartChannelLabel(touch.channel)} · {touch.recipient}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          {touch.messageCode} · {cartTransportLabel(touch.transport)} ·{' '}
                          {touch.createdAt}
                        </span>
                      </div>
                      {touch.failureReason && (
                        <p className="text-destructive text-copy-12 mt-1">
                          失败原因：{touch.failureReason}
                        </p>
                      )}
                      {writable && touch.status === 'REGISTERED' && (
                        <div className="mt-2 flex flex-wrap items-end gap-2">
                          <Field label="回填结果">
                            <select
                              className={control}
                              value={touchResultStatus}
                              onChange={(e) => setTouchResultStatus(e.target.value)}
                            >
                              <option value="SENT">已发出（人工交接后）</option>
                              <option value="FAILED">失败</option>
                            </select>
                          </Field>
                          <Field label="发出时刻" hint="留空即取登记时刻">
                            <input
                              className={control}
                              value={touchSentAt}
                              onChange={(e) => setTouchSentAt(e.target.value)}
                              placeholder="2026-10-10T09:00:00+08:00"
                            />
                          </Field>
                          {touchResultStatus === 'FAILED' && (
                            <Field label="失败原因" hint="登记失败必须写明原因">
                              <input
                                className={control}
                                value={touchFailure}
                                onChange={(e) => setTouchFailure(e.target.value)}
                              />
                            </Field>
                          )}
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void submitTouchResult(touch.id)}
                          >
                            回填结果
                          </Button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {writable && (
              <div className="border-border rounded-md border p-3">
                <p className="text-copy-13 font-medium">登记一次触达</p>
                <p className="text-muted-foreground text-copy-12 mt-1">
                  只能选**已启用**的话术；服务端还会再挡三道：已退订的一律不触达、
                  频次上限、挽回时点没过。越界会明确失败并写明原因。
                </p>
                <div className="mt-2 flex flex-wrap items-end gap-2">
                  <Field label="挽回话术">
                    <select
                      className={control}
                      value={touchMessageId}
                      onChange={(e) => setTouchMessageId(e.target.value)}
                    >
                      <option value="">（选择已启用的话术）</option>
                      {enabledMessages.map((row) => (
                        <option key={row.id} value={row.id}>
                          {row.messageCode} · {row.name} · {cartChannelLabel(row.channel)}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Button
                    size="sm"
                    disabled={busy || !touchMessageId}
                    onClick={() => void submitTouch()}
                  >
                    <Send className="mr-1 h-4 w-4" />
                    登记触达
                  </Button>
                </div>
                {enabledMessages.length === 0 && (
                  <p className="text-muted-foreground text-copy-12 mt-2">
                    当前没有「已启用」的话术 —— 话术需人工审核后启用才能被选用。
                  </p>
                )}
              </div>
            )}

            {writable && detail.abandonment.status !== 'RECOVERED' && (
              <div className="border-border rounded-md border p-3">
                <p className="text-copy-13 font-medium">登记挽回成交</p>
                <p className="text-muted-foreground text-copy-12 mt-1">
                  9 订单未交付，订单号由人工登记；本模块不编造下游订单 id。
                </p>
                <div className="mt-2 flex flex-wrap items-end gap-2">
                  <Field label="成交订单号">
                    <input
                      className={control}
                      value={recoveryOrderNo}
                      onChange={(e) => setRecoveryOrderNo(e.target.value)}
                      placeholder="SO-10021"
                    />
                  </Field>
                  <Field label="成交金额" hint="留空即取弃单金额">
                    <input
                      className={control}
                      value={recoveryAmount}
                      onChange={(e) => setRecoveryAmount(e.target.value)}
                    />
                  </Field>
                  <Field label="成交时刻">
                    <input
                      className={control}
                      value={recoveryAt}
                      onChange={(e) => setRecoveryAt(e.target.value)}
                      placeholder="2026-10-12T20:30:00+08:00"
                    />
                  </Field>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy || !recoveryOrderNo || !recoveryAt}
                    onClick={() => void submitRecovery()}
                  >
                    <CheckCircle2 className="mr-1 h-4 w-4" />
                    登记成交
                  </Button>
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 登记弃单 */}
      {writable && (
        <SectionCard
          title="登记弃单"
          description="平台购物车事件适配器未交付，这是目前唯一能进弃单的入口。平台店铺的登记会被明确拒绝。"
        >
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="店铺" hint="平台店铺会被拒（平台订单无购物车概念）">
                <select
                  className={control}
                  value={newShopId}
                  onChange={(e) => setNewShopId(e.target.value)}
                >
                  <option value="">（选择店铺）</option>
                  {shops.map((shop) => (
                    <option key={shop.id} value={shop.id}>
                      {shop.name} · {shop.platformCode} ·{' '}
                      {cartPlatformTypeLabel(shop.platformType)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="阶段">
                <select
                  className={control}
                  value={newStage}
                  onChange={(e) => setNewStage(e.target.value)}
                >
                  <option value="CART">只加购</option>
                  <option value="CHECKOUT">进了结账</option>
                </select>
              </Field>
              <Field label="购物车号" hint="同一购物车只登记一次；留空则不去重">
                <input
                  className={control}
                  value={newCartRef}
                  onChange={(e) => setNewCartRef(e.target.value)}
                  placeholder="例：cart-10021"
                />
              </Field>
              <Field label="买家标识">
                <input
                  className={control}
                  value={newBuyerRef}
                  onChange={(e) => setNewBuyerRef(e.target.value)}
                  placeholder="例：SHOP-BUYER-8821"
                />
              </Field>
              <Field label="买家邮箱" hint="邮件通道触达要用它">
                <input
                  className={control}
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  placeholder="buyer@example.com"
                />
              </Field>
              <Field label="买家手机号" hint="短信通道触达要用它">
                <input
                  className={control}
                  value={newPhone}
                  onChange={(e) => setNewPhone(e.target.value)}
                />
              </Field>
              <Field label="币种">
                <input
                  className={control}
                  value={newCurrency}
                  onChange={(e) => setNewCurrency(e.target.value)}
                  placeholder="USD"
                />
              </Field>
              <Field label="金额" hint="原样提交，页面不做计算">
                <input
                  className={control}
                  value={newAmount}
                  onChange={(e) => setNewAmount(e.target.value)}
                  placeholder="129.90"
                />
              </Field>
              <Field label="件数">
                <input
                  className={control}
                  value={newItemCount}
                  onChange={(e) => setNewItemCount(e.target.value)}
                  placeholder="2"
                />
              </Field>
              <Field label="事件时刻" hint="决定挽回时点：事件时刻 + 超时小时数">
                <input
                  className={control}
                  value={newAbandonedAt}
                  onChange={(e) => setNewAbandonedAt(e.target.value)}
                  placeholder="2026-10-10T08:00:00+08:00"
                />
              </Field>
            </div>
            <Field label="商品摘要">
              <input
                className={control}
                value={newSummary}
                onChange={(e) => setNewSummary(e.target.value)}
                placeholder="例：雨伞 ×2"
              />
            </Field>
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                disabled={busy || !newShopId || !newBuyerRef || !newAbandonedAt}
                onClick={() => void submitAbandonment()}
              >
                <ShoppingCart className="mr-1 h-4 w-4" />
                登记弃单
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={busy}
                onClick={() => void runSync()}
              >
                <RefreshCw className="mr-1 h-4 w-4" />
                从平台同步购物车事件
              </Button>
            </div>
            <p className="text-muted-foreground text-copy-12">
              「从平台同步」当前会明确失败：平台购物车 / 结账事件适配器未交付。
            </p>
          </div>
        </SectionCard>
      )}

      {/* ---------------------------------------------------------- 店铺平台类型 */}
      <SectionCard
        title="店铺平台类型"
        description="已知平台由系统判定（不可改）；未知平台码需要显式声明才能登记弃单。"
      >
        <ul className="divide-border divide-y">
          {shops.map((shop) => (
            <li key={shop.id} className="flex flex-wrap items-center gap-2 py-2">
              <Badge tone={shop.platformType === 'DTC' ? 'success' : 'warning'}>
                {cartPlatformTypeLabel(shop.platformType)}
              </Badge>
              <span className="font-medium">{shop.name}</span>
              <span className="text-muted-foreground text-copy-12">
                {shop.platformCode} · 来源 {shop.platformTypeSource}
              </span>
              {shop.platformType === 'UNDECLARED' && writable && (
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void declareShop(shop)}
                >
                  声明为独立站
                </Button>
              )}
            </li>
          ))}
        </ul>
      </SectionCard>

      {/* ---------------------------------------------------------- 挽回话术 */}
      <SectionCard
        title="挽回话术"
        description="草稿 → 已审核 → 已启用 → 已停用。只有「已启用」的话术能被触达流程选用。"
      >
        <div className="space-y-3">
          {(messages?.items.length ?? 0) === 0 ? (
            <EmptyState
              icon={<MessageSquare className="h-6 w-6" />}
              title="还没有挽回话术"
              description="话术需人工审核后启用才能被触达选用；新建的话术一律从草稿起步。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {messages?.items.map((row) => (
                <li key={row.id} className="py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge
                      tone={
                        row.status === 'ENABLED'
                          ? 'success'
                          : row.status === 'DRAFT'
                            ? 'neutral'
                            : 'warning'
                      }
                    >
                      {cartMessageStatusLabel(row.status)}
                    </Badge>
                    <span className="font-medium">{row.messageCode}</span>
                    <span className="text-muted-foreground text-copy-12">
                      {row.name} · {cartChannelLabel(row.channel)} · 版本 {row.version} · 已用{' '}
                      {row.usageCount} 次
                    </span>
                  </div>
                  {row.subject && (
                    <p className="text-copy-12 mt-1">标题：{row.subject}</p>
                  )}
                  <p className="text-muted-foreground text-copy-12 mt-1 line-clamp-2">{row.body}</p>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    {row.approvedAt
                      ? `审核于 ${row.approvedAt}（版本 ${row.approvedVersion}）`
                      : '未审核'}
                    {row.enabledAt ? ` · 启用于 ${row.enabledAt}` : ''}
                  </p>
                  {writable && (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {row.status === 'DRAFT' && (
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={busy}
                          onClick={() => void submitMessageAction(row, 'approve')}
                        >
                          审核通过
                        </Button>
                      )}
                      {row.status === 'APPROVED' && (
                        <Button
                          size="sm"
                          disabled={busy}
                          onClick={() => void submitMessageAction(row, 'enable')}
                        >
                          启用
                        </Button>
                      )}
                      {(row.status === 'APPROVED' || row.status === 'ENABLED') && (
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={busy}
                          onClick={() => void submitMessageAction(row, 'disable')}
                        >
                          停用
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={busy}
                        onClick={() => void submitMessageUpdate(row)}
                      >
                        保存（改了内容会回到草稿）
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}

          {writable && (
            <div className="border-border rounded-md border p-3">
              <p className="text-copy-13 font-medium">新建话术</p>
              <div className="mt-2 grid gap-3 md:grid-cols-4">
                <Field label="话术编号" hint="业务键，建后不可改">
                  <input
                    className={control}
                    value={messageCode}
                    onChange={(e) => setMessageCode(e.target.value)}
                    placeholder="CART-ABANDON-1"
                  />
                </Field>
                <Field label="名称">
                  <input
                    className={control}
                    value={messageName}
                    onChange={(e) => setMessageName(e.target.value)}
                    placeholder="弃单 1 小时提醒"
                  />
                </Field>
                <Field label="通道">
                  <select
                    className={control}
                    value={messageChannel}
                    onChange={(e) => setMessageChannel(e.target.value)}
                  >
                    <option value="EMAIL">邮件</option>
                    <option value="SMS">短信</option>
                    <option value="IN_APP">站内信</option>
                  </select>
                </Field>
                <Field label="标题">
                  <input
                    className={control}
                    value={messageSubject}
                    onChange={(e) => setMessageSubject(e.target.value)}
                  />
                </Field>
              </div>
              <div className="mt-2">
                <Field label="正文">
                  <textarea
                    className={control}
                    value={messageBody}
                    onChange={(e) => setMessageBody(e.target.value)}
                    rows={3}
                  />
                </Field>
              </div>
              <div className="mt-2">
                <Button
                  size="sm"
                  disabled={busy || !messageCode || !messageName}
                  onClick={() => void submitMessage()}
                >
                  新建草稿
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 退订名单 */}
      <SectionCard
        title="退订名单"
        description="登记之后这个联系人一律不触达 —— 服务端在发出去之前就查（不是只在报表里过滤）。"
      >
        <div className="space-y-3">
          {(unsubscribes?.items.length ?? 0) === 0 ? (
            <EmptyState
              icon={<Ban className="h-6 w-6" />}
              title="退订名单是空的"
              description="还没有登记过退订。登记后该联系人在任何通道上都不会被触达。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {unsubscribes?.items.map((row) => (
                <li key={row.id} className="flex flex-wrap items-center gap-2 py-2">
                  <Badge tone="warning">{cartChannelLabel(row.contactType)}</Badge>
                  <span className="font-medium">{row.contactValue}</span>
                  <span className="text-muted-foreground text-copy-12">
                    {row.reason || '（未写原因）'} · {row.unsubscribedAt}
                  </span>
                  {writable && (
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={busy}
                      onClick={() => void removeUnsubscribe(row.id)}
                    >
                      <Trash2 className="mr-1 h-4 w-4" />
                      取消退订
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {writable && (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="通道">
                <select
                  className={control}
                  value={unsubType}
                  onChange={(e) => setUnsubType(e.target.value)}
                >
                  <option value="EMAIL">邮件</option>
                  <option value="SMS">短信</option>
                  <option value="IN_APP">站内信</option>
                </select>
              </Field>
              <Field label="联系方式">
                <input
                  className={control}
                  value={unsubValue}
                  onChange={(e) => setUnsubValue(e.target.value)}
                  placeholder="buyer@example.com"
                />
              </Field>
              <Field label="原因">
                <input
                  className={control}
                  value={unsubReason}
                  onChange={(e) => setUnsubReason(e.target.value)}
                  placeholder="例：买家主动退订"
                />
              </Field>
              <Button
                size="sm"
                variant="secondary"
                disabled={busy || !unsubValue}
                onClick={() => void submitUnsubscribe()}
              >
                登记退订
              </Button>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 频次策略 */}
      <SectionCard
        title="触达频次上限与弃单超时"
        description="可配置、有默认值，DB 层另有天花板（调不到 999）。"
      >
        {policy && (
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-12">
              当前：每日 {policy.maxTouchesPerDay} 次 · 每 {policy.cycleDays} 天{' '}
              {policy.maxTouchesPerCycle} 次 · 弃单超时 {policy.abandonTimeoutHours} 小时
              {policy.customized ? '' : '（默认���，尚未自定义）'}
            </p>
            {writable && (
              <div className="flex flex-wrap items-end gap-2">
                <Field label="每日上限">
                  <input
                    className={control}
                    value={perDay}
                    onChange={(e) => setPerDay(e.target.value)}
                  />
                </Field>
                <Field label="每周期上限">
                  <input
                    className={control}
                    value={perCycle}
                    onChange={(e) => setPerCycle(e.target.value)}
                  />
                </Field>
                <Field label="周期长度（天）">
                  <input
                    className={control}
                    value={cycleDays}
                    onChange={(e) => setCycleDays(e.target.value)}
                  />
                </Field>
                <Field label="弃单超时（小时）">
                  <input
                    className={control}
                    value={timeoutHours}
                    onChange={(e) => setTimeoutHours(e.target.value)}
                  />
                </Field>
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => void submitPolicy()}>
                  保存策略
                </Button>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {capabilities?.boundaryNote && (
        <p className="text-muted-foreground text-copy-12 flex items-start gap-1">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          {capabilities.boundaryNote}
        </p>
      )}
    </div>
  );
}

/** 一个统计格子。 */
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-border rounded-md border p-3">
      <p className="text-muted-foreground text-copy-12">{label}</p>
      <p className="text-copy-20 font-semibold">{value}</p>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e && typeof e === 'object' && 'body' in e) {
    const body = (e as { body?: { detail?: string; message?: string } | null }).body;
    if (body?.detail) return body.detail;
    if (body?.message) return body.message;
  }
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
