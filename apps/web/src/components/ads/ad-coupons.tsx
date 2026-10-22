'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  changeAdCouponStatus,
  channelTypeLabel,
  claimAdCoupon,
  claimKindLabel,
  countText,
  couponCodeStatusLabel,
  couponStatusLabel,
  couponStatusTone,
  couponTypeLabel,
  emptyAdClaimInput,
  emptyAdCouponChannelInput,
  emptyAdCouponInput,
  emptyAdRedeemInput,
  fetchAdCoupon,
  fetchAdCouponCapabilities,
  fetchAdCouponChannels,
  fetchAdCouponCodes,
  fetchAdCouponOptions,
  fetchAdCoupons,
  generateAdCouponCodes,
  localDateTimeInput,
  money,
  ratioText,
  redeemAdCoupon,
  saveAdCoupon,
  saveAdCouponChannel,
  subsidyText,
  syncStatusLabel,
  timestampText,
  updateAdCoupon,
  validateAdClaim,
  validateAdCoupon,
  validateAdCouponChannel,
  validateAdRedeem,
} from './ad-coupon-model';
import type {
  AdClaimInputState,
  AdCouponCapabilities,
  AdCouponChannelInputState,
  AdCouponChannelRow,
  AdCouponCodePage,
  AdCouponDetail,
  AdCouponInputState,
  AdCouponOptions,
  AdCouponPage,
  AdRedeemInputState,
} from './ad-coupon-model';

/**
 * 14.4.1.2 优惠券与秒杀（券这一半）。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **门槛与面额是快照，不是实时价**。上游 5 定价在 catalog（跨服务不可读）⇒
 *    券的门槛 / 面额 / 折扣率是**建券时登记的定价口径**。页面必须写明这一点。
 * 2. **「本地已建」不是「平台侧已建」**。平台营销接口未交付 ⇒ 页面常驻
 *    「平台侧还没有这张券」。
 * 3. **核销必须带订单标识**。表单里订单标识是必填 —— 没有订单标识的核销写不进来。
 * 4. **不可测算不是 0**。买赠券的补贴成本显示「不可测算」，**不是 `0.00`**。
 * 5. **限领看的是累计**。「每人限领」说的是「这个人在这张券上累计领了几张」。
 */
export function AdCouponWorkspace() {
  const [capabilities, setCapabilities] = useState<AdCouponCapabilities | null>(null);
  const [options, setOptions] = useState<AdCouponOptions | null>(null);

  const [coupons, setCoupons] = useState<AdCouponPage | null>(null);
  const [detail, setDetail] = useState<AdCouponDetail | null>(null);
  const [codes, setCodes] = useState<AdCouponCodePage | null>(null);
  const [channels, setChannels] = useState<AdCouponChannelRow[]>([]);

  const [input, setInput] = useState<AdCouponInputState>(emptyAdCouponInput);
  const [errors, setErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [claimInput, setClaimInput] = useState<AdClaimInputState>(emptyAdClaimInput);
  const [claimErrors, setClaimErrors] = useState<string[]>([]);

  const [redeemInput, setRedeemInput] = useState<AdRedeemInputState>(emptyAdRedeemInput);
  const [redeemErrors, setRedeemErrors] = useState<string[]>([]);

  const [channelInput, setChannelInput] = useState<AdCouponChannelInputState>(
    emptyAdCouponChannelInput,
  );
  const [channelErrors, setChannelErrors] = useState<string[]>([]);

  const [generateCount, setGenerateCount] = useState('10');

  const [filterStatus, setFilterStatus] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterShop, setFilterShop] = useState('');
  const [filterChannel, setFilterChannel] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setCoupons(
        await fetchAdCoupons({
          status: filterStatus,
          couponType: filterType,
          shopCode: filterShop,
          channelType: filterChannel,
          keyword,
          page,
          size: 20,
        }),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filterStatus, filterType, filterShop, filterChannel, keyword, page]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdCouponCapabilities(),
          fetchAdCouponOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const unavailableDimensions = useMemo(
    () => (capabilities?.dimensions ?? []).filter((d) => !d.available),
    [capabilities],
  );

  async function refreshDetail(id: string) {
    const [next, nextCodes, nextChannels] = await Promise.all([
      fetchAdCoupon(id),
      fetchAdCouponCodes(id, { page: 0, size: 50 }),
      fetchAdCouponChannels(id),
    ]);
    setDetail(next);
    setCodes(nextCodes);
    setChannels(nextChannels);
  }

  async function onOpen(id: string) {
    setBusy(true);
    setError('');
    try {
      await refreshDetail(id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onEdit(id: string) {
    setBusy(true);
    setError('');
    try {
      const current = await fetchAdCoupon(id);
      setDetail(current);
      setEditingId(current.coupon.id);
      setInput({
        couponCode: current.coupon.couponCode,
        couponName: current.coupon.couponName,
        couponType: current.coupon.couponType,
        platformId: current.coupon.platformId,
        shopCode: current.coupon.shopCode,
        discountRate:
          current.coupon.discountRate === null ? '' : String(current.coupon.discountRate),
        discountValue:
          current.coupon.discountValue === null ? '' : String(current.coupon.discountValue),
        thresholdAmount:
          current.coupon.thresholdAmount === null ? '' : String(current.coupon.thresholdAmount),
        maxDiscountAmount:
          current.coupon.maxDiscountAmount === null ? '' : String(current.coupon.maxDiscountAmount),
        buyQuantity:
          current.coupon.buyQuantity === null ? '' : String(current.coupon.buyQuantity),
        giftQuantity:
          current.coupon.giftQuantity === null ? '' : String(current.coupon.giftQuantity),
        currency: current.coupon.currency,
        startAt: localDateTimeInput(current.coupon.startAt),
        endAt: localDateTimeInput(current.coupon.endAt),
        totalStock:
          current.coupon.totalStock === null ? '0' : String(current.coupon.totalStock),
        perUserLimit:
          current.coupon.perUserLimit === null ? '' : String(current.coupon.perUserLimit),
        codePrefix: '',
        codeLength: '8',
        codeCharset: '',
        note: current.coupon.note,
      });
      setErrors([]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    const validation = validateAdCoupon(input);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const payload = {
        couponCode: input.couponCode.trim(),
        couponName: input.couponName.trim(),
        couponType: input.couponType,
        platformId: input.platformId,
        shopCode: input.shopCode.trim(),
        discountRate:
          input.couponType === 'DISCOUNT_CODE' && input.discountRate.trim() !== ''
            ? Number(input.discountRate)
            : undefined,
        discountValue:
          (input.couponType === 'DISCOUNT_CODE' || input.couponType === 'FULL_REDUCTION') &&
          input.discountValue.trim() !== ''
            ? Number(input.discountValue)
            : undefined,
        thresholdAmount:
          input.couponType === 'FULL_REDUCTION' && input.thresholdAmount.trim() !== ''
            ? Number(input.thresholdAmount)
            : undefined,
        maxDiscountAmount:
          input.couponType !== 'BOGO' && input.maxDiscountAmount.trim() !== ''
            ? Number(input.maxDiscountAmount)
            : undefined,
        buyQuantity:
          input.couponType === 'BOGO' && input.buyQuantity.trim() !== ''
            ? Number(input.buyQuantity)
            : undefined,
        giftQuantity:
          input.couponType === 'BOGO' && input.giftQuantity.trim() !== ''
            ? Number(input.giftQuantity)
            : undefined,
        currency: input.currency.trim(),
        startAt: input.startAt === '' ? null : new Date(input.startAt).toISOString(),
        endAt: input.endAt === '' ? null : new Date(input.endAt).toISOString(),
        totalStock: Number(input.totalStock || '0'),
        perUserLimit: input.perUserLimit.trim() === '' ? undefined : Number(input.perUserLimit),
        codePrefix: input.codePrefix.trim(),
        codeLength: Number(input.codeLength),
        codeCharset: input.codeCharset.trim() === '' ? undefined : input.codeCharset.trim(),
        note: input.note,
      };
      const saved = editingId
        ? await updateAdCoupon(editingId, payload)
        : await saveAdCoupon(payload);
      setDetail(saved);
      setEditingId(null);
      setInput(emptyAdCouponInput());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onStatus(id: string, status: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await changeAdCouponStatus(id, { status, reason: '页面操作' }));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onGenerate() {
    if (!detail) return;
    const count = Number(generateCount);
    if (!Number.isInteger(count) || count <= 0) {
      setError('生成张数必须是大于 0 的整数');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await generateAdCouponCodes(detail.coupon.id, { count });
      await refreshDetail(detail.coupon.id);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onClaim() {
    if (!detail) return;
    const validation = validateAdClaim(claimInput);
    setClaimErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await claimAdCoupon(detail.coupon.id, {
        claimKind: claimInput.claimKind,
        recipientKey: claimInput.recipientKey.trim(),
        channelType: claimInput.channelType,
        channelId: claimInput.channelId.trim() === '' ? undefined : claimInput.channelId.trim(),
        quantity: Number(claimInput.quantity),
      });
      setClaimInput(emptyAdClaimInput());
      await refreshDetail(detail.coupon.id);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRedeem() {
    if (!detail) return;
    const validation = validateAdRedeem(redeemInput);
    setRedeemErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await redeemAdCoupon(detail.coupon.id, {
        orderRef: redeemInput.orderRef.trim(),
        code: redeemInput.code.trim() === '' ? undefined : redeemInput.code.trim(),
        orderAmount:
          redeemInput.orderAmount.trim() === '' ? undefined : Number(redeemInput.orderAmount),
      });
      setRedeemInput(emptyAdRedeemInput());
      await refreshDetail(detail.coupon.id);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveChannel() {
    if (!detail) return;
    const validation = validateAdCouponChannel(channelInput);
    setChannelErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdCouponChannel(detail.coupon.id, {
        channelType: channelInput.channelType,
        placement: channelInput.placement.trim(),
        planStartAt:
          channelInput.planStartAt === ''
            ? null
            : new Date(channelInput.planStartAt).toISOString(),
        planEndAt:
          channelInput.planEndAt === '' ? null : new Date(channelInput.planEndAt).toISOString(),
        plannedReach:
          channelInput.plannedReach.trim() === ''
            ? undefined
            : Number(channelInput.plannedReach),
        status: channelInput.status,
        note: channelInput.note,
      });
      setChannelInput(emptyAdCouponChannelInput());
      await refreshDetail(detail.coupon.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">优惠券</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          管理<strong>折扣码 / 满减 / 买赠</strong>三类券：<strong>生成码</strong>、
          <strong>设置库存与限领</strong>、<strong>投放渠道（站内 / 站外 / 社媒）</strong>，
          以及<strong>领取与核销</strong>跟踪。<strong>核销必须与订单绑定</strong> ——
          否则评估不了活动真实效果与补贴成本。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 能力声明 ================= */}
      <Section title="能力声明">
        {capabilities ? (
          <>
            {unavailableDimensions.length > 0 ? (
              <div className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
                <strong>🔴 以下维度当前不可用</strong>
                <ul className="list-disc pl-5">
                  {unavailableDimensions.map((d) => (
                    <li key={d.dimension}>
                      <strong>{d.description}</strong> —— 责任模块：{d.ownerModule || '—'}
                      <br />
                      影响：{d.impact || '—'}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            <p className="text-muted-foreground text-sm">{capabilities.pricingSnapshotNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.syncNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.recipientNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.orderBindingNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.subsidyNote}</p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">维度</th>
                  <th className="py-1">是否已交付</th>
                  <th className="py-1">来源</th>
                  <th className="py-1">责任模块</th>
                  <th className="py-1">不交付的影响</th>
                </tr>
              </thead>
              <tbody>
                {capabilities.dimensions.map((d) => (
                  <tr key={d.dimension} className="border-t">
                    <td className="py-1">{d.dimension}</td>
                    <td className={`py-1 ${d.available ? 'text-emerald-600' : 'text-amber-600'}`}>
                      {d.available ? '已交付' : '未交付'}
                    </td>
                    <td className="py-1 text-xs">{d.source || '—'}</td>
                    <td className="py-1 text-xs">{d.ownerModule || '—'}</td>
                    <td className="py-1 text-xs">{d.impact || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
        )}
      </Section>

      {/* ================= 券列表 ================= */}
      <Section title="券记录">
        <div className="flex flex-wrap gap-2">
          <Field label="状态">
            <select
              className="rounded border px-2 py-1"
              value={filterStatus}
              onChange={(e) => {
                setPage(0);
                setFilterStatus(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.statuses ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="券类型">
            <select
              className="rounded border px-2 py-1"
              value={filterType}
              onChange={(e) => {
                setPage(0);
                setFilterType(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.couponTypes ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1"
              value={filterShop}
              onChange={(e) => {
                setPage(0);
                setFilterShop(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.shopCodes ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="投放渠道">
            <select
              className="rounded border px-2 py-1"
              value={filterChannel}
              onChange={(e) => {
                setPage(0);
                setFilterChannel(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.channelTypes ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键字">
            <input
              className="rounded border px-2 py-1"
              value={keyword}
              onChange={(e) => {
                setPage(0);
                setKeyword(e.target.value);
              }}
            />
          </Field>
        </div>

        {loading && !coupons ? (
          <p className="text-muted-foreground text-sm">正在加载券记录…</p>
        ) : coupons && coupons.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有券记录。用下面的表单登记一张：券类型、面额 / 门槛、库存、限领与生效时间窗。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">类型</th>
                <th className="py-1">折扣口径（快照）</th>
                <th className="py-1">生效时间窗</th>
                <th className="py-1">库存 / 已生成 / 已领 / 已核销</th>
                <th className="py-1">限领</th>
                <th className="py-1">状态</th>
                <th className="py-1">平台同步</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {coupons?.items.map((c) => (
                <tr key={c.id} className="border-t">
                  <td className="py-1">
                    {c.couponCode} / {c.couponName}
                  </td>
                  <td className="py-1">{couponTypeLabel(c.couponType)}</td>
                  <td className="py-1 text-xs">
                    {c.couponType === 'DISCOUNT_CODE'
                      ? c.discountRate !== null
                        ? ratioText(c.discountRate)
                        : money(c.discountValue, c.currency)
                      : c.couponType === 'FULL_REDUCTION'
                        ? `满 ${money(c.thresholdAmount, c.currency)} 减 ${money(c.discountValue, c.currency)}`
                        : `买 ${countText(c.buyQuantity)} 送 ${countText(c.giftQuantity)}`}
                    {c.maxDiscountAmount !== null ? (
                      <span className="text-muted-foreground">
                        {' '}
                        · 封顶 {money(c.maxDiscountAmount, c.currency)}
                      </span>
                    ) : null}
                  </td>
                  <td className="py-1 text-xs">
                    {timestampText(c.startAt)} ~ {timestampText(c.endAt)}
                    {c.withinScheduleWindow ? (
                      <span className="text-emerald-600"> · 在生效期内</span>
                    ) : null}
                  </td>
                  <td className="py-1 text-xs">
                    {countText(c.totalStock)} / {countText(c.generatedCount)} /{' '}
                    {countText(c.claimedCount)} / {countText(c.redeemedCount)}
                  </td>
                  <td className="py-1 text-xs">
                    {c.perUserLimit === null ? '不限领' : `${countText(c.perUserLimit)} 张 / 人`}
                  </td>
                  <td className={`py-1 ${couponStatusTone(c.status)}`}>
                    {couponStatusLabel(c.status)}
                  </td>
                  <td className="py-1 text-xs">
                    {syncStatusLabel(c.platformSyncStatus)}
                    {c.pendingPlatformWriteback ? (
                      <span className="text-amber-600"> · 待回写平台</span>
                    ) : null}
                  </td>
                  <td className="py-1">
                    <div className="flex gap-1">
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onOpen(c.id)}
                      >
                        详情
                      </button>
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onEdit(c.id)}
                      >
                        编辑
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {coupons ? (
          <div className="flex items-center gap-2">
            <p className="text-muted-foreground text-xs">
              共 {coupons.total} 条（本页 {coupons.items.length} 条，第 {coupons.page + 1} 页）
            </p>
            <button
              className="rounded border px-2 py-1 text-xs"
              disabled={busy || coupons.page <= 0}
              onClick={() => setPage(Math.max(page - 1, 0))}
            >
              上一页
            </button>
            <button
              className="rounded border px-2 py-1 text-xs"
              disabled={busy || coupons.page + 1 >= Math.ceil(coupons.total / coupons.size)}
              onClick={() => setPage(page + 1)}
            >
              下一页
            </button>
          </div>
        ) : null}
      </Section>

      {/* ================= 券详情 ================= */}
      {detail ? (
        <Section title={`券详情 · ${detail.coupon.couponCode}`}>
          <div className="grid gap-2 text-sm md:grid-cols-4">
            <p>
              <span className="text-muted-foreground text-xs">状态</span>
              <br />
              <span className={couponStatusTone(detail.coupon.status)}>
                {couponStatusLabel(detail.coupon.status)}
              </span>
            </p>
            <p>
              <span className="text-muted-foreground text-xs">平台同步状态</span>
              <br />
              {syncStatusLabel(detail.coupon.platformSyncStatus)}
              {detail.coupon.pendingPlatformWriteback ? '（平台侧还没有这张券）' : ''}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">库存</span>
              <br />
              {countText(detail.coupon.totalStock)} · 已生成{' '}
              {countText(detail.coupon.generatedCount)} · 已领{' '}
              {countText(detail.coupon.claimedCount)} · 已核销{' '}
              {countText(detail.coupon.redeemedCount)}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">此刻可领</span>
              <br />
              {countText(detail.coupon.claimableCount)} 张
            </p>
          </div>

          <p className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
            <strong>🔴 门槛与面额是快照</strong>：{detail.pricingSnapshotNote}
          </p>

          <div className="flex flex-wrap gap-2">
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.coupon.id, 'ACTIVE')}
            >
              上线
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.coupon.id, 'PAUSED')}
            >
              暂停
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.coupon.id, 'ENDED')}
            >
              结束
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.coupon.id, 'CANCELLED')}
            >
              停用
            </button>
          </div>

          <h3 className="font-medium text-sm">核销统计（真聚合）</h3>
          <div className="grid gap-2 text-sm md:grid-cols-4">
            <p>
              <span className="text-muted-foreground text-xs">已生成 / 已领 / 已核销</span>
              <br />
              {detail.statistics.generatedCount} / {detail.statistics.claimedCount} /{' '}
              {detail.statistics.redeemedCount}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">核销率</span>
              <br />
              {detail.statistics.redemptionRate === null
                ? '不可比（还没有领取）'
                : ratioText(detail.statistics.redemptionRate)}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">补贴成本合计</span>
              <br />
              {subsidyText(detail.statistics.totalSubsidyCost)}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">不可测算笔数</span>
              <br />
              <span className={detail.statistics.unmeasurableRedemptions > 0 ? 'text-amber-600' : ''}>
                {detail.statistics.unmeasurableRedemptions} 笔
              </span>
            </p>
          </div>
          {detail.statistics.unmeasurableRedemptions > 0 ? (
            <p className="text-amber-600 text-xs">
              ⛔ 这 {detail.statistics.unmeasurableRedemptions} 笔核销的补贴成本**不可测算**
              （买赠券缺成本价，成本价在 05 定价，跨服务不可读）—— 页面**不会**把它们显示成 0。
            </p>
          ) : null}

          {detail.statistics.channelBreakdown.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">渠道</th>
                  <th className="py-1">投放位</th>
                  <th className="py-1">领取数</th>
                  <th className="py-1">核销数</th>
                  <th className="py-1">补贴成本</th>
                </tr>
              </thead>
              <tbody>
                {detail.statistics.channelBreakdown.map((s) => (
                  <tr key={`${s.channelType}-${s.placement}`} className="border-t">
                    <td className="py-1">{channelTypeLabel(s.channelType)}</td>
                    <td className="py-1 text-xs">{s.placement || '—'}</td>
                    <td className="py-1">{s.claimCount}</td>
                    <td className="py-1">{s.redemptionCount}</td>
                    <td className="py-1">{subsidyText(s.subsidyCost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}

          <h3 className="font-medium text-sm">投放渠道与投放计划</h3>
          {channels.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              这张券还没有登记投放渠道。用下面的表单登记：站内 / 站外 / 社媒 + 投放位 + 投放时间窗。
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">渠道</th>
                  <th className="py-1">投放位</th>
                  <th className="py-1">投放时间窗</th>
                  <th className="py-1">计划触达</th>
                  <th className="py-1">实际领取 / 核销</th>
                  <th className="py-1">状态</th>
                </tr>
              </thead>
              <tbody>
                {channels.map((ch) => (
                  <tr key={ch.id} className="border-t">
                    <td className="py-1">{channelTypeLabel(ch.channelType)}</td>
                    <td className="py-1 text-xs">{ch.placement}</td>
                    <td className="py-1 text-xs">
                      {timestampText(ch.planStartAt)} ~ {timestampText(ch.planEndAt)}
                    </td>
                    <td className="py-1 text-xs">{countText(ch.plannedReach)}</td>
                    <td className="py-1 text-xs">
                      {ch.actualClaimCount} / {ch.actualRedemptionCount}
                    </td>
                    <td className="py-1 text-xs">{ch.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 className="font-medium text-sm">券码（生成码）</h3>
          <div className="flex items-end gap-2">
            <Field label="生成张数（受剩余库存约束，超库存会被服务端拒绝）">
              <input
                className="rounded border px-2 py-1"
                value={generateCount}
                onChange={(e) => setGenerateCount(e.target.value)}
              />
            </Field>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onGenerate()}
            >
              批量生成券码
            </button>
          </div>
          {codes && codes.items.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有券码 —— 先生成再领取。</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">券码</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">领取人</th>
                  <th className="py-1">领取时刻</th>
                  <th className="py-1">核销订单</th>
                </tr>
              </thead>
              <tbody>
                {codes?.items.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1 font-mono text-xs">{c.code}</td>
                    <td className="py-1 text-xs">{couponCodeStatusLabel(c.status)}</td>
                    <td className="py-1 text-xs">{c.claimantKey || '—'}</td>
                    <td className="py-1 text-xs">{timestampText(c.claimedAt)}</td>
                    <td className="py-1 text-xs">{c.orderRef || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 className="font-medium text-sm">最近领取记录</h3>
          {detail.recentClaims.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有领取记录。</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">方式</th>
                  <th className="py-1">领取人 / 收件人</th>
                  <th className="py-1">渠道</th>
                  <th className="py-1">券码</th>
                  <th className="py-1">张数</th>
                  <th className="py-1">时刻</th>
                </tr>
              </thead>
              <tbody>
                {detail.recentClaims.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1 text-xs">{claimKindLabel(c.claimKind)}</td>
                    <td className="py-1 text-xs">{c.recipientKey}</td>
                    <td className="py-1 text-xs">{channelTypeLabel(c.channelType)}</td>
                    <td className="py-1 font-mono text-xs">{c.code}</td>
                    <td className="py-1 text-xs">{countText(c.quantity)}</td>
                    <td className="py-1 text-xs">{timestampText(c.claimedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 className="font-medium text-sm">最近核销记录（绑定订单标识）</h3>
          {detail.recentRedemptions.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有核销记录。</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">订单标识</th>
                  <th className="py-1">券码</th>
                  <th className="py-1">订单金额</th>
                  <th className="py-1">让利额</th>
                  <th className="py-1">补贴成本</th>
                  <th className="py-1">核销时刻</th>
                </tr>
              </thead>
              <tbody>
                {detail.recentRedemptions.map((r) => (
                  <tr key={r.id} className="border-t">
                    <td className="py-1 text-xs">{r.orderRef}</td>
                    <td className="py-1 font-mono text-xs">{r.code}</td>
                    <td className="py-1 text-xs">{money(r.orderAmount, r.currency)}</td>
                    <td className="py-1 text-xs">{money(r.redeemedAmount, r.currency)}</td>
                    <td className="py-1 text-xs">{subsidyText(r.subsidyCost)}</td>
                    <td className="py-1 text-xs">{timestampText(r.redeemedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      ) : null}

      {/* ================= 领取 / 定向发放 ================= */}
      {detail ? (
        <Section title="领取 / 定向发放">
          <p className="text-muted-foreground text-xs">
            限领判据是「**这个人在这张券上累计领了几张**」，超出服务端直接拒绝；
            定向发放（PUSH）填收件人标识，发放记录与券码都真的落库。
          </p>
          <div className="grid gap-2 md:grid-cols-4">
            <Field label="领取方式">
              <select
                className="rounded border px-2 py-1"
                value={claimInput.claimKind}
                onChange={(e) => setClaimInput({ ...claimInput, claimKind: e.target.value })}
              >
                {(options?.claimKinds ?? []).map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="领取人 / 收件人标识">
              <input
                className="rounded border px-2 py-1"
                value={claimInput.recipientKey}
                onChange={(e) =>
                  setClaimInput({ ...claimInput, recipientKey: e.target.value })
                }
              />
            </Field>
            <Field label="领取渠道">
              <select
                className="rounded border px-2 py-1"
                value={claimInput.channelType}
                onChange={(e) =>
                  setClaimInput({ ...claimInput, channelType: e.target.value })
                }
              >
                <option value="ON_SITE">站内</option>
                <option value="OFF_SITE">站外</option>
                <option value="SOCIAL">社媒</option>
                <option value="DIRECT">定向发放</option>
              </select>
            </Field>
            <Field label="领取张数">
              <input
                className="rounded border px-2 py-1"
                value={claimInput.quantity}
                onChange={(e) => setClaimInput({ ...claimInput, quantity: e.target.value })}
              />
            </Field>
          </div>
          {claimErrors.length > 0 ? (
            <ul className="text-destructive text-sm">
              {claimErrors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          ) : null}
          <button
            className="rounded border px-2 py-1"
            disabled={busy}
            onClick={() => void onClaim()}
          >
            领取 / 发放
          </button>
        </Section>
      ) : null}

      {/* ================= 核销 ================= */}
      {detail ? (
        <Section title="核销（必须与订单绑定）">
          <div className="grid gap-2 md:grid-cols-3">
            <Field label="订单标识（必填）">
              <input
                className="rounded border px-2 py-1"
                value={redeemInput.orderRef}
                onChange={(e) => setRedeemInput({ ...redeemInput, orderRef: e.target.value })}
              />
            </Field>
            <Field label="券码（留空取该领取人已领未核销的码）">
              <input
                className="rounded border px-2 py-1"
                value={redeemInput.code}
                onChange={(e) => setRedeemInput({ ...redeemInput, code: e.target.value })}
              />
            </Field>
            <Field label="订单金额（折扣率券 / 满减券必填）">
              <input
                className="rounded border px-2 py-1"
                value={redeemInput.orderAmount}
                onChange={(e) =>
                  setRedeemInput({ ...redeemInput, orderAmount: e.target.value })
                }
              />
            </Field>
          </div>
          {redeemErrors.length > 0 ? (
            <ul className="text-destructive text-sm">
              {redeemErrors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          ) : null}
          <button
            className="rounded border px-2 py-1"
            disabled={busy}
            onClick={() => void onRedeem()}
          >
            核销
          </button>
        </Section>
      ) : null}

      {/* ================= 投放渠道 ================= */}
      {detail ? (
        <Section title="登记投放渠道 / 投放计划">
          <div className="grid gap-2 md:grid-cols-3">
            <Field label="渠道类型">
              <select
                className="rounded border px-2 py-1"
                value={channelInput.channelType}
                onChange={(e) =>
                  setChannelInput({ ...channelInput, channelType: e.target.value })
                }
              >
                {(options?.channelTypes ?? []).map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="投放位">
              <input
                className="rounded border px-2 py-1"
                value={channelInput.placement}
                onChange={(e) =>
                  setChannelInput({ ...channelInput, placement: e.target.value })
                }
              />
            </Field>
            <Field label="计划触达量（可空）">
              <input
                className="rounded border px-2 py-1"
                value={channelInput.plannedReach}
                onChange={(e) =>
                  setChannelInput({ ...channelInput, plannedReach: e.target.value })
                }
              />
            </Field>
            <Field label="投放开始时间">
              <input
                className="rounded border px-2 py-1"
                type="datetime-local"
                value={channelInput.planStartAt}
                onChange={(e) =>
                  setChannelInput({ ...channelInput, planStartAt: e.target.value })
                }
              />
            </Field>
            <Field label="投放结束时间">
              <input
                className="rounded border px-2 py-1"
                type="datetime-local"
                value={channelInput.planEndAt}
                onChange={(e) =>
                  setChannelInput({ ...channelInput, planEndAt: e.target.value })
                }
              />
            </Field>
            <Field label="投放状态">
              <select
                className="rounded border px-2 py-1"
                value={channelInput.status}
                onChange={(e) => setChannelInput({ ...channelInput, status: e.target.value })}
              >
                <option value="PLANNED">已排期</option>
                <option value="RUNNING">投放中</option>
                <option value="PAUSED">已暂停</option>
                <option value="FINISHED">已结束</option>
                <option value="CANCELLED">已取消</option>
              </select>
            </Field>
          </div>
          {channelErrors.length > 0 ? (
            <ul className="text-destructive text-sm">
              {channelErrors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          ) : null}
          <button
            className="rounded border px-2 py-1"
            disabled={busy}
            onClick={() => void onSaveChannel()}
          >
            登记投放渠道
          </button>
        </Section>
      ) : null}

      {/* ================= 登记 / 修改券 ================= */}
      <Section title={editingId ? '修改券规则' : '登记券'}>
        <div className="grid gap-2 md:grid-cols-3">
          <Field label="券编码（租户内唯一，创建后不可改）">
            <input
              className="rounded border px-2 py-1"
              value={input.couponCode}
              disabled={editingId !== null}
              onChange={(e) => setInput({ ...input, couponCode: e.target.value })}
            />
          </Field>
          <Field label="券名称">
            <input
              className="rounded border px-2 py-1"
              value={input.couponName}
              onChange={(e) => setInput({ ...input, couponName: e.target.value })}
            />
          </Field>
          <Field label="券类型">
            <select
              className="rounded border px-2 py-1"
              value={input.couponType}
              onChange={(e) => setInput({ ...input, couponType: e.target.value })}
            >
              {(options?.couponTypes ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="参与店铺编码">
            <select
              className="rounded border px-2 py-1"
              value={input.shopCode}
              onChange={(e) => setInput({ ...input, shopCode: e.target.value })}
            >
              <option value="">请选择</option>
              {(options?.shopCodes ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="平台">
            <select
              className="rounded border px-2 py-1"
              value={input.platformId}
              onChange={(e) => setInput({ ...input, platformId: e.target.value })}
            >
              <option value="">请选择</option>
              {(options?.platforms ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.code} · {p.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1"
              value={input.currency}
              onChange={(e) => setInput({ ...input, currency: e.target.value })}
            />
          </Field>
          <Field label="折扣率（折扣码用，0 < r <= 1）">
            <input
              className="rounded border px-2 py-1"
              value={input.discountRate}
              onChange={(e) => setInput({ ...input, discountRate: e.target.value })}
            />
          </Field>
          <Field label="面额 / 减免额">
            <input
              className="rounded border px-2 py-1"
              value={input.discountValue}
              onChange={(e) => setInput({ ...input, discountValue: e.target.value })}
            />
          </Field>
          <Field label="满减门槛">
            <input
              className="rounded border px-2 py-1"
              value={input.thresholdAmount}
              onChange={(e) => setInput({ ...input, thresholdAmount: e.target.value })}
            />
          </Field>
          <Field label="单笔让利封顶（可空 = 不封顶）">
            <input
              className="rounded border px-2 py-1"
              value={input.maxDiscountAmount}
              onChange={(e) => setInput({ ...input, maxDiscountAmount: e.target.value })}
            />
          </Field>
          <Field label="买赠：买 N">
            <input
              className="rounded border px-2 py-1"
              value={input.buyQuantity}
              onChange={(e) => setInput({ ...input, buyQuantity: e.target.value })}
            />
          </Field>
          <Field label="买赠：送 M">
            <input
              className="rounded border px-2 py-1"
              value={input.giftQuantity}
              onChange={(e) => setInput({ ...input, giftQuantity: e.target.value })}
            />
          </Field>
          <Field label="库存（可生成的券码总数）">
            <input
              className="rounded border px-2 py-1"
              value={input.totalStock}
              onChange={(e) => setInput({ ...input, totalStock: e.target.value })}
            />
          </Field>
          <Field label="每人限领张数（留空 = 不限领）">
            <input
              className="rounded border px-2 py-1"
              value={input.perUserLimit}
              onChange={(e) => setInput({ ...input, perUserLimit: e.target.value })}
            />
          </Field>
          <Field label="券码前缀">
            <input
              className="rounded border px-2 py-1"
              value={input.codePrefix}
              onChange={(e) => setInput({ ...input, codePrefix: e.target.value })}
            />
          </Field>
          <Field label="券码随机段长度（4-32）">
            <input
              className="rounded border px-2 py-1"
              value={input.codeLength}
              onChange={(e) => setInput({ ...input, codeLength: e.target.value })}
            />
          </Field>
          <Field label="生效时间">
            <input
              className="rounded border px-2 py-1"
              type="datetime-local"
              value={input.startAt}
              onChange={(e) => setInput({ ...input, startAt: e.target.value })}
            />
          </Field>
          <Field label="失效时间">
            <input
              className="rounded border px-2 py-1"
              type="datetime-local"
              value={input.endAt}
              onChange={(e) => setInput({ ...input, endAt: e.target.value })}
            />
          </Field>
          <Field label="备注">
            <input
              className="rounded border px-2 py-1"
              value={input.note}
              onChange={(e) => setInput({ ...input, note: e.target.value })}
            />
          </Field>
        </div>

        {errors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}

        <div className="flex gap-2">
          <button
            className="rounded border px-2 py-1"
            disabled={busy}
            onClick={() => void onSave()}
          >
            {editingId ? '保存修改' : '登记券'}
          </button>
          {editingId ? (
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => {
                setEditingId(null);
                setInput(emptyAdCouponInput());
                setErrors([]);
              }}
            >
              取消编辑
            </button>
          ) : null}
        </div>
      </Section>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-muted-foreground text-xs">{label}</span>
      {children}
    </label>
  );
}
