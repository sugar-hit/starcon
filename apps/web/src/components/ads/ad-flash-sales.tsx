'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  changeAdFlashSaleStatus,
  claimAdFlashSale,
  emptyAdFlashSaleClaimInput,
  emptyAdFlashSaleInput,
  emptyAdFlashSaleRedeemInput,
  fetchAdFlashSale,
  fetchAdFlashSaleCapabilities,
  fetchAdFlashSaleOptions,
  fetchAdFlashSales,
  flashClaimStatusLabel,
  flashCountText,
  flashLocalDateTimeInput,
  flashMoney,
  flashStatusLabel,
  flashStatusTone,
  flashSubsidyText,
  flashSyncStatusLabel,
  flashTimestampText,
  redeemAdFlashSale,
  saveAdFlashSale,
  updateAdFlashSale,
  validateAdFlashSale,
  validateAdFlashSaleClaim,
  validateAdFlashSaleRedeem,
} from './ad-flash-sale-model';
import type {
  AdFlashSaleCapabilities,
  AdFlashSaleClaimInputState,
  AdFlashSaleDetail,
  AdFlashSaleInputState,
  AdFlashSaleOptions,
  AdFlashSalePage,
  AdFlashSaleRedeemInputState,
} from './ad-flash-sale-model';

/**
 * 14.4.1.2 优惠券与秒杀（秒杀这一半）。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **秒杀价与原价都是快照**。上游 5 定价在 catalog（跨服务不可读）⇒ 页面要写明
 *    「建活动时登记的定价口径」。
 * 2. **「本地已建」不是「平台侧已建」**。平台营销接口未交付 ⇒ 页面常驻
 *    「平台侧还没有这个秒杀活动」。
 * 3. **核销必须带订单标识**，且**不可比不是 0**：原价没登记时让利额显示「不可比」，
 *    **不是 `0.00`**。
 */
export function AdFlashSaleWorkspace() {
  const [capabilities, setCapabilities] = useState<AdFlashSaleCapabilities | null>(null);
  const [options, setOptions] = useState<AdFlashSaleOptions | null>(null);

  const [sales, setSales] = useState<AdFlashSalePage | null>(null);
  const [detail, setDetail] = useState<AdFlashSaleDetail | null>(null);

  const [input, setInput] = useState<AdFlashSaleInputState>(emptyAdFlashSaleInput);
  const [errors, setErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [claimInput, setClaimInput] = useState<AdFlashSaleClaimInputState>(
    emptyAdFlashSaleClaimInput,
  );
  const [claimErrors, setClaimErrors] = useState<string[]>([]);

  const [redeemInput, setRedeemInput] = useState<AdFlashSaleRedeemInputState>(
    emptyAdFlashSaleRedeemInput,
  );
  const [redeemErrors, setRedeemErrors] = useState<string[]>([]);

  const [filterStatus, setFilterStatus] = useState('');
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
      setSales(
        await fetchAdFlashSales({
          status: filterStatus,
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
  }, [filterStatus, filterShop, filterChannel, keyword, page]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdFlashSaleCapabilities(),
          fetchAdFlashSaleOptions(),
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

  async function onOpen(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchAdFlashSale(id));
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
      const current = await fetchAdFlashSale(id);
      setDetail(current);
      setEditingId(current.sale.id);
      setInput({
        saleCode: current.sale.saleCode,
        saleName: current.sale.saleName,
        platformId: current.sale.platformId,
        shopCode: current.sale.shopCode,
        listingId: current.sale.listingId,
        flashPrice: current.sale.flashPrice === null ? '' : String(current.sale.flashPrice),
        originalPrice:
          current.sale.originalPrice === null ? '' : String(current.sale.originalPrice),
        currency: current.sale.currency,
        startAt: flashLocalDateTimeInput(current.sale.startAt),
        endAt: flashLocalDateTimeInput(current.sale.endAt),
        totalStock:
          current.sale.totalStock === null ? '0' : String(current.sale.totalStock),
        perUserLimit:
          current.sale.perUserLimit === null ? '' : String(current.sale.perUserLimit),
        channelType: current.sale.channelType,
        placement: current.sale.placement,
        note: current.sale.note,
      });
      setErrors([]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    const validation = validateAdFlashSale(input);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const payload = {
        saleCode: input.saleCode.trim(),
        saleName: input.saleName.trim(),
        platformId: input.platformId,
        shopCode: input.shopCode.trim(),
        listingId: input.listingId.trim(),
        flashPrice: Number(input.flashPrice),
        originalPrice:
          input.originalPrice.trim() === '' ? undefined : Number(input.originalPrice),
        currency: input.currency.trim(),
        startAt: input.startAt === '' ? null : new Date(input.startAt).toISOString(),
        endAt: input.endAt === '' ? null : new Date(input.endAt).toISOString(),
        totalStock: Number(input.totalStock || '0'),
        perUserLimit: input.perUserLimit.trim() === '' ? undefined : Number(input.perUserLimit),
        channelType: input.channelType,
        placement: input.placement.trim(),
        note: input.note,
      };
      const saved = editingId
        ? await updateAdFlashSale(editingId, payload)
        : await saveAdFlashSale(payload);
      setDetail(saved);
      setEditingId(null);
      setInput(emptyAdFlashSaleInput());
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
      setDetail(await changeAdFlashSaleStatus(id, { status, reason: '页面操作' }));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onClaim() {
    if (!detail) return;
    const validation = validateAdFlashSaleClaim(claimInput);
    setClaimErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      setDetail(
        await claimAdFlashSale(detail.sale.id, {
          recipientKey: claimInput.recipientKey.trim(),
          quantity: Number(claimInput.quantity),
        }),
      );
      setClaimInput(emptyAdFlashSaleClaimInput());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onRedeem() {
    if (!detail) return;
    const validation = validateAdFlashSaleRedeem(redeemInput);
    setRedeemErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      setDetail(
        await redeemAdFlashSale(detail.sale.id, {
          orderRef: redeemInput.orderRef.trim(),
          claimId: redeemInput.claimId.trim() === '' ? undefined : redeemInput.claimId.trim(),
        }),
      );
      setRedeemInput(emptyAdFlashSaleRedeemInput());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">秒杀</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          管理<strong>秒杀活动</strong>：秒杀价与原价、<strong>秒杀库存</strong>、
          <strong>每人限领</strong>、限时时间窗与<strong>投放渠道（站内 / 站外 / 社媒）</strong>，
          以及领取与核销跟踪。<strong>核销必须与订单绑定</strong>。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

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
            <p className="text-muted-foreground text-sm">{capabilities.orderBindingNote}</p>
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

      <Section title="秒杀活动">
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

        {loading && !sales ? (
          <p className="text-muted-foreground text-sm">正在加载秒杀活动…</p>
        ) : sales && sales.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有秒杀活动。用下面的表单登记一个：秒杀商品、秒杀价、库存、限领、限时与投放渠道。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">商品</th>
                <th className="py-1">秒杀价 / 原价</th>
                <th className="py-1">时间窗</th>
                <th className="py-1">库存 / 已领 / 已核销</th>
                <th className="py-1">限领</th>
                <th className="py-1">渠道</th>
                <th className="py-1">状态</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {sales?.items.map((s) => (
                <tr key={s.id} className="border-t">
                  <td className="py-1">
                    {s.saleCode} / {s.saleName}
                  </td>
                  <td className="py-1 text-xs">
                    {s.listingTitle || '—'}
                    <span className="text-muted-foreground"> {s.listingId}</span>
                  </td>
                  <td className="py-1 text-xs">
                    {flashMoney(s.flashPrice, s.currency)} /{' '}
                    <span className="text-muted-foreground">{flashMoney(s.originalPrice)}</span>
                  </td>
                  <td className="py-1 text-xs">
                    {flashTimestampText(s.startAt)} ~ {flashTimestampText(s.endAt)}
                    {s.withinScheduleWindow ? (
                      <span className="text-emerald-600"> · 在活动期</span>
                    ) : null}
                  </td>
                  <td className="py-1 text-xs">
                    {flashCountText(s.totalStock)} / {flashCountText(s.claimedQuantity)} /{' '}
                    {flashCountText(s.redeemedQuantity)}
                  </td>
                  <td className="py-1 text-xs">
                    {s.perUserLimit === null ? '不限领' : `${flashCountText(s.perUserLimit)} 件 / 人`}
                  </td>
                  <td className="py-1 text-xs">{s.channelType}</td>
                  <td className={`py-1 ${flashStatusTone(s.status)}`}>{flashStatusLabel(s.status)}</td>
                  <td className="py-1">
                    <div className="flex gap-1">
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onOpen(s.id)}
                      >
                        详情
                      </button>
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onEdit(s.id)}
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
        {sales ? (
          <div className="flex items-center gap-2">
            <p className="text-muted-foreground text-xs">
              共 {sales.total} 条（本页 {sales.items.length} 条，第 {sales.page + 1} 页）
            </p>
            <button
              className="rounded border px-2 py-1 text-xs"
              disabled={busy || sales.page <= 0}
              onClick={() => setPage(Math.max(page - 1, 0))}
            >
              上一页
            </button>
            <button
              className="rounded border px-2 py-1 text-xs"
              disabled={busy || sales.page + 1 >= Math.ceil(sales.total / sales.size)}
              onClick={() => setPage(page + 1)}
            >
              下一页
            </button>
          </div>
        ) : null}
      </Section>

      {detail ? (
        <Section title={`秒杀详情 · ${detail.sale.saleCode}`}>
          <div className="grid gap-2 text-sm md:grid-cols-4">
            <p>
              <span className="text-muted-foreground text-xs">状态</span>
              <br />
              <span className={flashStatusTone(detail.sale.status)}>
                {flashStatusLabel(detail.sale.status)}
              </span>
            </p>
            <p>
              <span className="text-muted-foreground text-xs">平台同步状态</span>
              <br />
              {flashSyncStatusLabel(detail.sale.platformSyncStatus)}
              {detail.sale.pendingPlatformWriteback ? '（平台侧还没有这个秒杀活动）' : ''}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">库存</span>
              <br />
              {flashCountText(detail.sale.totalStock)} · 已领{' '}
              {flashCountText(detail.sale.claimedQuantity)} · 已核销{' '}
              {flashCountText(detail.sale.redeemedQuantity)} · 剩余{' '}
              {flashCountText(detail.sale.remainingStock)}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">核销率</span>
              <br />
              {detail.statistics.redemptionRate === null
                ? '不可比（还没有领取）'
                : `${(detail.statistics.redemptionRate * 100).toFixed(2)}%`}
            </p>
          </div>

          <p className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
            <strong>🔴 秒杀价与原价都是快照</strong>：{detail.pricingSnapshotNote}
          </p>

          <div className="flex flex-wrap gap-2">
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.sale.id, 'ACTIVE')}
            >
              上线
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.sale.id, 'PAUSED')}
            >
              暂停
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.sale.id, 'ENDED')}
            >
              结束
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.sale.id, 'CANCELLED')}
            >
              停用
            </button>
          </div>

          <h3 className="font-medium text-sm">领取与核销记录</h3>
          {detail.recentClaims.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有领取记录。</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">领取人</th>
                  <th className="py-1">件数</th>
                  <th className="py-1">订单标识</th>
                  <th className="py-1">让利额</th>
                  <th className="py-1">补贴成本</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">领取时刻</th>
                </tr>
              </thead>
              <tbody>
                {detail.recentClaims.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1 text-xs">{c.recipientKey}</td>
                    <td className="py-1 text-xs">{flashCountText(c.quantity)}</td>
                    <td className="py-1 text-xs">{c.orderRef || '—'}</td>
                    <td className="py-1 text-xs">{flashMoney(c.redeemedAmount, detail.sale.currency)}</td>
                    <td className="py-1 text-xs">{flashSubsidyText(c.subsidyCost)}</td>
                    <td className="py-1 text-xs">{flashClaimStatusLabel(c.status)}</td>
                    <td className="py-1 text-xs">{flashTimestampText(c.claimedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="text-muted-foreground text-xs">
            让利额显示「不可比」表示**原价没登记**（不是 0.00）；补贴成本显示「不可测算」表示
            本地拿不到成本价。{detail.statistics.marginNote}
          </p>
        </Section>
      ) : null}

      {detail ? (
        <Section title="领取秒杀">
          <p className="text-muted-foreground text-xs">
            库存是真扣的，限领判据是「这个人在这个秒杀上累计领了几件」，超出服务端直接拒绝。
          </p>
          <div className="grid gap-2 md:grid-cols-2">
            <Field label="领取人标识">
              <input
                className="rounded border px-2 py-1"
                value={claimInput.recipientKey}
                onChange={(e) =>
                  setClaimInput({ ...claimInput, recipientKey: e.target.value })
                }
              />
            </Field>
            <Field label="领取件数">
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
            领取
          </button>
        </Section>
      ) : null}

      {detail ? (
        <Section title="核销（必须与订单绑定）">
          <div className="grid gap-2 md:grid-cols-2">
            <Field label="订单标识（必填）">
              <input
                className="rounded border px-2 py-1"
                value={redeemInput.orderRef}
                onChange={(e) => setRedeemInput({ ...redeemInput, orderRef: e.target.value })}
              />
            </Field>
            <Field label="领取记录 id（留空取最近一条未核销的领取）">
              <input
                className="rounded border px-2 py-1"
                value={redeemInput.claimId}
                onChange={(e) => setRedeemInput({ ...redeemInput, claimId: e.target.value })}
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

      <Section title={editingId ? '修改秒杀活动' : '登记秒杀活动'}>
        <div className="grid gap-2 md:grid-cols-3">
          <Field label="秒杀编码（租户内唯一，创建后不可改）">
            <input
              className="rounded border px-2 py-1"
              value={input.saleCode}
              disabled={editingId !== null}
              onChange={(e) => setInput({ ...input, saleCode: e.target.value })}
            />
          </Field>
          <Field label="秒杀名称">
            <input
              className="rounded border px-2 py-1"
              value={input.saleName}
              onChange={(e) => setInput({ ...input, saleName: e.target.value })}
            />
          </Field>
          <Field label="秒杀商品（listing id）">
            <input
              className="rounded border px-2 py-1"
              value={input.listingId}
              onChange={(e) => setInput({ ...input, listingId: e.target.value })}
            />
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
          <Field label="秒杀价">
            <input
              className="rounded border px-2 py-1"
              value={input.flashPrice}
              onChange={(e) => setInput({ ...input, flashPrice: e.target.value })}
            />
          </Field>
          <Field label="原价（留空 = 不可比，不是 0）">
            <input
              className="rounded border px-2 py-1"
              value={input.originalPrice}
              onChange={(e) => setInput({ ...input, originalPrice: e.target.value })}
            />
          </Field>
          <Field label="秒杀库存">
            <input
              className="rounded border px-2 py-1"
              value={input.totalStock}
              onChange={(e) => setInput({ ...input, totalStock: e.target.value })}
            />
          </Field>
          <Field label="每人限领件数（留空 = 不限领）">
            <input
              className="rounded border px-2 py-1"
              value={input.perUserLimit}
              onChange={(e) => setInput({ ...input, perUserLimit: e.target.value })}
            />
          </Field>
          <Field label="投放渠道">
            <select
              className="rounded border px-2 py-1"
              value={input.channelType}
              onChange={(e) => setInput({ ...input, channelType: e.target.value })}
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
              value={input.placement}
              onChange={(e) => setInput({ ...input, placement: e.target.value })}
            />
          </Field>
          <Field label="开始时间">
            <input
              className="rounded border px-2 py-1"
              type="datetime-local"
              value={input.startAt}
              onChange={(e) => setInput({ ...input, startAt: e.target.value })}
            />
          </Field>
          <Field label="结束时间">
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
            {editingId ? '保存修改' : '登记秒杀'}
          </button>
          {editingId ? (
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => {
                setEditingId(null);
                setInput(emptyAdFlashSaleInput());
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
