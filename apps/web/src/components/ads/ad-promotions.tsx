'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  changeAdPromotionStatus,
  checkAdPromotion,
  discountText,
  discountTone,
  emptyAdGuardrailInput,
  emptyAdPromotionInput,
  fetchAdPromotion,
  fetchAdPromotionCapabilities,
  fetchAdPromotionGuardrails,
  fetchAdPromotionOptions,
  fetchAdPromotions,
  localDateTimeInput,
  money,
  promotionTypeLabel,
  ruleLabel,
  saveAdPromotion,
  saveAdPromotionGuardrail,
  severityLabel,
  severityTone,
  statusLabel,
  statusTone,
  syncStatusLabel,
  timestampText,
  updateAdPromotion,
  validateAdGuardrail,
  validateAdPromotion,
} from './ad-promotion-model';
import type {
  AdGuardrailInputState,
  AdPromotionCapabilities,
  AdPromotionDetail,
  AdPromotionInputState,
  AdPromotionOptions,
  AdPromotionPage,
  AdPromotionGuardrailPage,
} from './ad-promotion-model';

/**
 * 14.4.1.1 促销列表。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **毛利当前不可用，必须常驻说明**。上游 5 定价在 catalog（跨服务不可读）且没有促销价测算
 *    端点 ⇒ 拿不到成本，「是否亏损」判不了。页面要让人看见「毛利未测算」，
 *    而不是让人以为「促销毛利已经算好了」。**绝不能显示一个假的 0 毛利**。
 * 2. **「本地已建」不是「平台侧已建」**。平台促销 API 未交付 ⇒ 平台同步状态常驻
 *    「本地已建，平台侧还没有」。
 * 3. **折后价可算、不可比要写清楚**。折后价 / 叠加折后价是本地算的（基准价 × (1 − 折扣率)），
 *    没登记基准价时显示「不可比」，**不是 0.00**。
 * 4. **拦截是真拦**。折扣幅度超阈值、折后价低于下限、促销叠加三条规则会**拦住排期与上线**，
 *    改完必须重跑检查才会放行。
 */
export function AdPromotionWorkspace() {
  const [capabilities, setCapabilities] = useState<AdPromotionCapabilities | null>(null);
  const [options, setOptions] = useState<AdPromotionOptions | null>(null);

  const [promotions, setPromotions] = useState<AdPromotionPage | null>(null);
  const [detail, setDetail] = useState<AdPromotionDetail | null>(null);

  const [guardrails, setGuardrails] = useState<AdPromotionGuardrailPage | null>(null);

  const [input, setInput] = useState<AdPromotionInputState>(emptyAdPromotionInput);
  const [errors, setErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [guardrailInput, setGuardrailInput] = useState<AdGuardrailInputState>(emptyAdGuardrailInput);
  const [guardrailErrors, setGuardrailErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [promotionPage, guardrailPage] = await Promise.all([
        fetchAdPromotions({ page: 0, size: 50 }),
        fetchAdPromotionGuardrails({ page: 0, size: 50 }),
      ]);
      setPromotions(promotionPage);
      setGuardrails(guardrailPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdPromotionCapabilities(),
          fetchAdPromotionOptions(),
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

  const marginDimension = useMemo(
    () => capabilities?.dimensions.find((d) => d.dimension === 'gross_margin') ?? null,
    [capabilities],
  );

  async function onOpen(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchAdPromotion(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 把一条促销读回表单（改定义）。促销编码创建后不可改 ⇒ 表单里禁用。 */
  async function onEdit(id: string) {
    setBusy(true);
    setError('');
    try {
      const current = await fetchAdPromotion(id);
      setDetail(current);
      setEditingId(current.promotion.id);
      setInput({
        promotionCode: current.promotion.promotionCode,
        promotionName: current.promotion.promotionName,
        promotionType: current.promotion.promotionType,
        platformId: current.promotion.platformId,
        shopCode: current.promotion.shopCode,
        discountRate:
          current.promotion.discountRate === null ? '' : String(current.promotion.discountRate),
        discountAmount:
          current.promotion.discountAmount === null ? '' : String(current.promotion.discountAmount),
        budgetCap: current.promotion.budgetCap === null ? '' : String(current.promotion.budgetCap),
        currency: current.promotion.currency,
        startAt: localDateTimeInput(current.promotion.startAt),
        endAt: localDateTimeInput(current.promotion.endAt),
        products: current.products.map((p) => ({
          listingId: p.listingId,
          basePrice: p.basePrice === null ? '' : String(p.basePrice),
          note: p.note,
        })),
        note: current.promotion.note,
      });
      setErrors([]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    const validation = validateAdPromotion(input);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      const payload = {
        promotionCode: input.promotionCode.trim(),
        promotionName: input.promotionName.trim(),
        promotionType: input.promotionType,
        platformId: input.platformId,
        shopCode: input.shopCode.trim(),
        discountRate:
          input.promotionType === 'PERCENT_OFF' && input.discountRate.trim() !== ''
            ? Number(input.discountRate)
            : undefined,
        discountAmount:
          input.promotionType === 'FIXED_AMOUNT_OFF' && input.discountAmount.trim() !== ''
            ? Number(input.discountAmount)
            : undefined,
        budgetCap: input.budgetCap.trim() === '' ? undefined : Number(input.budgetCap),
        currency: input.currency.trim(),
        startAt: input.startAt === '' ? null : new Date(input.startAt).toISOString(),
        endAt: input.endAt === '' ? null : new Date(input.endAt).toISOString(),
        products: input.products
          .filter((p) => p.listingId.trim() !== '')
          .map((p) => ({
            listingId: p.listingId.trim(),
            basePrice: p.basePrice.trim() === '' ? undefined : Number(p.basePrice),
            currency: input.currency.trim(),
            note: p.note,
          })),
        note: input.note,
      };
      const saved = editingId
        ? await updateAdPromotion(editingId, payload)
        : await saveAdPromotion(payload);
      setDetail(saved);
      setEditingId(null);
      setInput(emptyAdPromotionInput());
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
      setDetail(await changeAdPromotionStatus(id, { status, reason: '页面操作' }));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onCheck(id: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await checkAdPromotion(id));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveGuardrail() {
    const validation = validateAdGuardrail(guardrailInput);
    setGuardrailErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveAdPromotionGuardrail({
        guardrailCode: guardrailInput.guardrailCode.trim(),
        guardrailName: guardrailInput.guardrailName.trim(),
        maxDiscountRate:
          guardrailInput.maxDiscountRate.trim() === ''
            ? undefined
            : Number(guardrailInput.maxDiscountRate),
        minNetPrice:
          guardrailInput.minNetPrice.trim() === '' ? undefined : Number(guardrailInput.minNetPrice),
        blockOnStacking: guardrailInput.blockOnStacking,
        status: 'ACTIVE',
        note: guardrailInput.note,
      });
      setGuardrailInput(emptyAdGuardrailInput());
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
        <h1 className="text-2xl font-semibold">促销列表</h1>
        <p className="text-muted-foreground max-w-4xl text-sm">
          管理平台促销活动：<strong>促销类型</strong>、<strong>适用商品</strong>、
          <strong>折扣幅度</strong>、<strong>起止时间</strong>、<strong>预算上限</strong>、
          <strong>参与店铺</strong>，以及<strong>平台同步状态</strong>。折扣叠加后亏损是常态，
          本模块按本地可算的折后价与折扣幅度做<strong>真拦截 / 真预警</strong>。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {/* ================= 🔴 毛利维度当前不可用（常驻） ================= */}
      <Section title="能力声明">
        {capabilities && marginDimension && !marginDimension.available ? (
          <p className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
            <strong>🔴 毛利 / 利润当前不可用（未测算）</strong>：{marginDimension.description}
            <br />
            责任模块：<strong>{marginDimension.ownerModule}</strong>
            <br />
            影响：{marginDimension.impact}
          </p>
        ) : null}
        {capabilities ? (
          <>
            <p className="text-muted-foreground text-sm">{capabilities.netPriceNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.stackingNote}</p>
            <p className="text-muted-foreground text-sm">{capabilities.syncNote}</p>
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

      {/* ================= 促销列表 ================= */}
      <Section title="促销记录">
        {loading && !promotions ? (
          <p className="text-muted-foreground text-sm">正在加载促销记录…</p>
        ) : promotions && promotions.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有促销记录。用下面的表单登记一条：促销类型、折扣幅度、起止时间、预算上限、
            参与店铺与适用商品。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">类型</th>
                <th className="py-1">折扣幅度</th>
                <th className="py-1">起止时间</th>
                <th className="py-1">预算上限</th>
                <th className="py-1">参与店铺</th>
                <th className="py-1">适用商品</th>
                <th className="py-1">状态</th>
                <th className="py-1">平台同步</th>
                <th className="py-1">拦截 / 预警</th>
                <th className="py-1">操作</th>
              </tr>
            </thead>
            <tbody>
              {promotions?.items.map((p) => (
                <tr key={p.id} className="border-t">
                  <td className="py-1">
                    {p.promotionCode} / {p.promotionName}
                  </td>
                  <td className="py-1">{promotionTypeLabel(p.promotionType)}</td>
                  <td className={`py-1 ${discountTone(p.discountRate)}`}>
                    {p.promotionType === 'FIXED_AMOUNT_OFF'
                      ? money(p.discountAmount, p.currency)
                      : discountText(p.discountRate)}
                  </td>
                  <td className="py-1 text-xs">
                    {timestampText(p.startAt)} ~ {timestampText(p.endAt)}
                    {p.withinScheduleWindow ? (
                      <span className="text-emerald-600"> · 在促销期内</span>
                    ) : null}
                  </td>
                  <td className="py-1">{p.budgetCap === null ? '不限' : money(p.budgetCap, p.currency)}</td>
                  <td className="py-1 text-xs">{p.shopCode}</td>
                  <td className="py-1">{p.productCount}</td>
                  <td className={`py-1 ${statusTone(p.status)}`}>{statusLabel(p.status)}</td>
                  <td className="py-1 text-xs">
                    {syncStatusLabel(p.platformSyncStatus)}
                    {p.pendingPlatformWriteback ? (
                      <span className="text-amber-600"> · 待回写平台</span>
                    ) : null}
                  </td>
                  <td className="py-1 text-xs">
                    <span className="text-destructive">拦截 {p.blockedCount}</span> /{' '}
                    <span className="text-amber-600">预警 {p.warningCount}</span>
                  </td>
                  <td className="py-1">
                    <div className="flex gap-1">
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onOpen(p.id)}
                      >
                        详情
                      </button>
                      <button
                        className="rounded border px-2 py-1"
                        disabled={busy}
                        onClick={() => void onEdit(p.id)}
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
        {promotions ? (
          <p className="text-muted-foreground text-xs">
            共 {promotions.total} 条（本页 {promotions.items.length} 条，第 {promotions.page + 1} 页）
          </p>
        ) : null}
      </Section>

      {/* ================= 促销详情 ================= */}
      {detail ? (
        <Section title={`促销详情 · ${detail.promotion.promotionCode}`}>
          <div className="grid gap-2 text-sm md:grid-cols-3">
            <p>
              <span className="text-muted-foreground text-xs">状态</span>
              <br />
              <span className={statusTone(detail.promotion.status)}>
                {statusLabel(detail.promotion.status)}
              </span>
            </p>
            <p>
              <span className="text-muted-foreground text-xs">平台同步状态</span>
              <br />
              {syncStatusLabel(detail.promotion.platformSyncStatus)}
              {detail.promotion.pendingPlatformWriteback ? '（平台侧还没有这个促销）' : ''}
            </p>
            <p>
              <span className="text-muted-foreground text-xs">最近一次检查</span>
              <br />
              {timestampText(detail.promotion.lastCheckedAt)}
            </p>
          </div>

          <p className="rounded border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
            <strong>🔴 毛利未测算</strong>：{detail.marginNote}
          </p>

          <div className="flex flex-wrap gap-2">
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onCheck(detail.promotion.id)}
            >
              重跑拦截 / 预警检查
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.promotion.id, 'SCHEDULED')}
            >
              排期
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.promotion.id, 'ACTIVE')}
            >
              上线
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.promotion.id, 'PAUSED')}
            >
              暂停
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.promotion.id, 'ENDED')}
            >
              结束
            </button>
            <button
              className="rounded border px-2 py-1"
              disabled={busy}
              onClick={() => void onStatus(detail.promotion.id, 'CANCELLED')}
            >
              取消
            </button>
          </div>

          <h3 className="font-medium text-sm">适用商品与折后价</h3>
          {detail.products.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              这条促销还没有挂适用商品。适用商品是**多对多**的：同一个商品可以被多个促销覆盖 ——
              那正是「折扣叠加」的来源。
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">商品</th>
                  <th className="py-1">基准价</th>
                  <th className="py-1">折后价</th>
                  <th className="py-1">折扣幅度</th>
                  <th className="py-1">叠加促销</th>
                  <th className="py-1">叠加折后价</th>
                </tr>
              </thead>
              <tbody>
                {detail.products.map((p) => (
                  <tr key={p.id} className="border-t">
                    <td className="py-1">
                      {p.title} <span className="text-muted-foreground text-xs">{p.externalItemId}</span>
                    </td>
                    <td className="py-1">{money(p.basePrice, p.currency)}</td>
                    <td className="py-1">{money(p.netPrice, p.currency)}</td>
                    <td className={`py-1 ${discountTone(p.discountDepth)}`}>
                      {discountText(p.discountDepth)}
                    </td>
                    <td className="py-1">
                      {p.stackedPromotionCount > 0 ? (
                        <span className="text-amber-600">{p.stackedPromotionCount} 个</span>
                      ) : (
                        '无'
                      )}
                    </td>
                    <td className="py-1">{money(p.stackedNetPrice, p.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 className="font-medium text-sm">
            最近一次检查的结论（拦截 {detail.blockedCount} · 预警 {detail.warningCount}）
          </h3>
          {detail.checks.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              最近一次检查没有发现问题（这不等于「毛利没问题」—— 毛利当前拿不到成本，未测算）。
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">规则</th>
                  <th className="py-1">结论</th>
                  <th className="py-1">观测值 / 阈值</th>
                  <th className="py-1">说明</th>
                </tr>
              </thead>
              <tbody>
                {detail.checks.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1">{ruleLabel(c.ruleCode)}</td>
                    <td className={`py-1 ${severityTone(c.severity)}`}>{severityLabel(c.severity)}</td>
                    <td className="py-1 text-xs">
                      {c.observedValue === null ? '—' : c.observedValue} /{' '}
                      {c.thresholdValue === null ? '—' : c.thresholdValue}
                    </td>
                    <td className="py-1 text-xs">{c.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      ) : null}

      {/* ================= 登记 / 修改促销 ================= */}
      <Section title={editingId ? '修改促销定义' : '登记促销'}>
        <div className="grid gap-2 md:grid-cols-3">
          <Field label="促销编码（租户内唯一，创建后不可改）">
            <input
              className="rounded border px-2 py-1"
              value={input.promotionCode}
              disabled={editingId !== null}
              onChange={(e) => setInput({ ...input, promotionCode: e.target.value })}
            />
          </Field>
          <Field label="促销名称">
            <input
              className="rounded border px-2 py-1"
              value={input.promotionName}
              onChange={(e) => setInput({ ...input, promotionName: e.target.value })}
            />
          </Field>
          <Field label="促销类型">
            <select
              className="rounded border px-2 py-1"
              value={input.promotionType}
              onChange={(e) => setInput({ ...input, promotionType: e.target.value })}
            >
              {(options?.promotionTypes ?? []).map((o) => (
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
          <Field label="折扣率（百分比促销；0.2 = 让利 20%）">
            <input
              className="rounded border px-2 py-1"
              value={input.discountRate}
              disabled={input.promotionType !== 'PERCENT_OFF'}
              onChange={(e) => setInput({ ...input, discountRate: e.target.value })}
            />
          </Field>
          <Field label="固定减免额（固定额促销）">
            <input
              className="rounded border px-2 py-1"
              value={input.discountAmount}
              disabled={input.promotionType !== 'FIXED_AMOUNT_OFF'}
              onChange={(e) => setInput({ ...input, discountAmount: e.target.value })}
            />
          </Field>
          <Field label="预算上限（留空 = 不限，不是 0）">
            <input
              className="rounded border px-2 py-1"
              value={input.budgetCap}
              onChange={(e) => setInput({ ...input, budgetCap: e.target.value })}
            />
          </Field>
          <Field label="开始时间">
            <input
              type="datetime-local"
              className="rounded border px-2 py-1"
              value={input.startAt}
              onChange={(e) => setInput({ ...input, startAt: e.target.value })}
            />
          </Field>
          <Field label="结束时间">
            <input
              type="datetime-local"
              className="rounded border px-2 py-1"
              value={input.endAt}
              onChange={(e) => setInput({ ...input, endAt: e.target.value })}
            />
          </Field>
        </div>

        <h3 className="font-medium text-sm">适用商品（多对多）</h3>
        <p className="text-muted-foreground text-xs">
          基准价是**促销定义里登记的输入**（listing 没有价格列 —— 售价归 05 定价）。
          留空 ⇒ 折后价**不可比**（不是 0），促销叠加判定仍然生效。
        </p>
        {input.products.map((product, index) => (
          <div key={index} className="grid gap-2 md:grid-cols-3">
            <Field label="listing id">
              <input
                className="rounded border px-2 py-1"
                value={product.listingId}
                onChange={(e) => {
                  const products = [...input.products];
                  products[index] = { ...product, listingId: e.target.value };
                  setInput({ ...input, products });
                }}
              />
            </Field>
            <Field label="基准价（可空 = 不可比）">
              <input
                className="rounded border px-2 py-1"
                value={product.basePrice}
                onChange={(e) => {
                  const products = [...input.products];
                  products[index] = { ...product, basePrice: e.target.value };
                  setInput({ ...input, products });
                }}
              />
            </Field>
            <Field label="备注">
              <input
                className="rounded border px-2 py-1"
                value={product.note}
                onChange={(e) => {
                  const products = [...input.products];
                  products[index] = { ...product, note: e.target.value };
                  setInput({ ...input, products });
                }}
              />
            </Field>
          </div>
        ))}
        <button
          className="rounded border px-2 py-1"
          onClick={() =>
            setInput({
              ...input,
              products: [...input.products, { listingId: '', basePrice: '', note: '' }],
            })
          }
        >
          添加适用商品
        </button>

        {errors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}

        <div className="flex gap-2">
          <button className="rounded border px-2 py-1" disabled={busy} onClick={() => void onSave()}>
            {editingId ? '保存修改' : '登记促销'}
          </button>
          {editingId ? (
            <button
              className="rounded border px-2 py-1"
              onClick={() => {
                setEditingId(null);
                setInput(emptyAdPromotionInput());
                setErrors([]);
              }}
            >
              取消修改
            </button>
          ) : null}
        </div>
      </Section>

      {/* ================= 护栏规则（拦截 / 预警的阈值） ================= */}
      <Section title="护栏规则（拦截 / 预警阈值）">
        {guardrails && guardrails.items.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有护栏规则。没有规则就没有拦截 —— 折扣幅度、折后价下限、促销叠加这三类判定
            都要阈值才能判。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">编码 / 名称</th>
                <th className="py-1">折扣幅度上限</th>
                <th className="py-1">折后价下限</th>
                <th className="py-1">叠加是否拦截</th>
                <th className="py-1">状态</th>
              </tr>
            </thead>
            <tbody>
              {guardrails?.items.map((g) => (
                <tr key={g.id} className="border-t">
                  <td className="py-1">
                    {g.guardrailCode} / {g.guardrailName}
                  </td>
                  <td className="py-1">{discountText(g.maxDiscountRate)}</td>
                  <td className="py-1">{g.minNetPrice === null ? '不设' : money(g.minNetPrice)}</td>
                  <td className="py-1">{g.blockOnStacking ? '拦截' : '只预警'}</td>
                  <td className="py-1">{g.status === 'ACTIVE' ? '生效中' : '已停用'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="grid gap-2 md:grid-cols-3">
          <Field label="规则编码（租户内唯一）">
            <input
              className="rounded border px-2 py-1"
              value={guardrailInput.guardrailCode}
              onChange={(e) => setGuardrailInput({ ...guardrailInput, guardrailCode: e.target.value })}
            />
          </Field>
          <Field label="规则名称">
            <input
              className="rounded border px-2 py-1"
              value={guardrailInput.guardrailName}
              onChange={(e) => setGuardrailInput({ ...guardrailInput, guardrailName: e.target.value })}
            />
          </Field>
          <Field label="折扣幅度上限（0.8 = 让利不超过八成）">
            <input
              className="rounded border px-2 py-1"
              value={guardrailInput.maxDiscountRate}
              onChange={(e) =>
                setGuardrailInput({ ...guardrailInput, maxDiscountRate: e.target.value })
              }
            />
          </Field>
          <Field label="折后价下限">
            <input
              className="rounded border px-2 py-1"
              value={guardrailInput.minNetPrice}
              onChange={(e) => setGuardrailInput({ ...guardrailInput, minNetPrice: e.target.value })}
            />
          </Field>
          <Field label="促销叠加">
            <select
              className="rounded border px-2 py-1"
              value={guardrailInput.blockOnStacking ? 'BLOCK' : 'WARN'}
              onChange={(e) =>
                setGuardrailInput({
                  ...guardrailInput,
                  blockOnStacking: e.target.value === 'BLOCK',
                })
              }
            >
              <option value="BLOCK">拦截</option>
              <option value="WARN">只预警</option>
            </select>
          </Field>
        </div>

        {guardrailErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {guardrailErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}

        <button
          className="rounded border px-2 py-1"
          disabled={busy}
          onClick={() => void onSaveGuardrail()}
        >
          保存护栏规则
        </button>
        <p className="text-muted-foreground text-xs">
          一个租户同一时刻只有一套生效的护栏：保存新的一套会自动停用旧的。
        </p>
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
