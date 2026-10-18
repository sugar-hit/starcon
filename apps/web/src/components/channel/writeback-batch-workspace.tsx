'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ShopView,
  WritebackBatchPreview,
  WritebackBatchView,
  WritebackTargetPage,
  WritebackTargetView,
} from '../../../../../packages/api-contracts/src/index';
import {
  BATCH_HARD_LIMIT,
  KIND_LABELS,
  KIND_TARGET_TYPES,
  OUTCOME_LABELS,
  TARGET_TYPE_LABELS,
  batchLine,
  batchScopeText,
  buildBatchInput,
  emptyBatchForm,
  emptyPerTargetForm,
  executeBatch,
  fetchBatches,
  fetchBatch,
  fetchShops,
  fetchTargets,
  itemResultText,
  itemTone,
  issueLabel,
  issueTone,
  localBlockReason,
  outcomeTone,
  previewBatch,
  previewHeadline,
  price,
  quantity,
  setTargetStatus,
  targetSummaryRows,
  unknownHint,
  upsertTarget,
  type BatchForm,
  type PerTargetForm,
} from './writeback-batch-model';

/**
 * 2.5.2.1 常用回写操作。
 *
 * <p>蓝图：「提供高频回写的快捷入口与批量操作（批量改库存、批量改价、批量下架、批量回传物流），
 * 支持按店铺与筛选条件批量执行。」「生成 N 条独立回写任务，逐条记录结果，**部分失败不影响其余**。」
 *
 * <p><b>页面围绕那句加粗句组织</b>：
 *
 * <ol>
 *   <li><b>执行前先试算</b>：同一套筛选与校验，不落任何一行 —— 对着几百条盲点「执行」是本模块
 *       最贵的误操作，试算把「会命中多少、其中多少条会被拦下」摆到执行之前。</li>
 *   <li><b>范围必须明说</b>：勾选与筛选两种范围的爆炸半径差一个量级，界面上永远显示当前这一次
 *       按什么范围发（{@link batchScopeText}），而不是让人回猜自己刚才勾了什么。</li>
 *   <li><b>逐条结果分两种说法</b>：「没发出去」（生成前被拦下，压根没发往平台）与
 *       「已发出 · 平台已拒绝」必须分开 —— 后者要去平台侧查，前者不用。</li>
 *   <li><b>结果未知单列一类</b>，并带一句「先核验再决定重发」：UNKNOWN 画成提醒色而不是危险色，
 *       因为把它算成失败就是在诱导重试，而重试正是重复扣减 / 重复发货的来源（2.5.1.2）。</li>
 * </ol>
 *
 * <p>页面**刻意没有**「一键重试整批」：批量不等于事务，重发要按 2.5.1.1 的任务清单逐条判断 ——
 * 对一个含 UNKNOWN 的批次一键重发，正是本模块最容易造成资损的动作。
 */
export function WritebackBatchWorkspace() {
  const [shops, setShops] = useState<ShopView[]>([]);
  const [pool, setPool] = useState<WritebackTargetPage | null>(null);
  const [batches, setBatches] = useState<WritebackBatchView[]>([]);
  const [batchOutcome, setBatchOutcome] = useState('');
  const [detail, setDetail] = useState<WritebackBatchView | null>(null);
  const [preview, setPreview] = useState<WritebackBatchPreview | null>(null);

  const [shopId, setShopId] = useState('');
  const [targetType, setTargetType] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<BatchForm>(emptyBatchForm());

  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」。
      const params: Parameters<typeof fetchTargets>[0] = { page, size: 20 };
      if (shopId) params.shopId = shopId;
      if (targetType) params.targetType = targetType;
      if (status) params.status = status;
      if (keyword.trim()) params.keyword = keyword.trim();
      const query: Parameters<typeof fetchBatches>[0] = { page: 0, size: 10 };
      if (batchOutcome) query.outcome = batchOutcome;
      const [targets, batchPage, shopList] = await Promise.all([
        fetchTargets(params),
        fetchBatches(query),
        fetchShops(),
      ]);
      setPool(targets);
      setBatches(batchPage.items);
      setShops(shopList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, shopId, targetType, status, keyword, batchOutcome]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<BatchForm>) => {
    setForm((f) => ({ ...f, ...next }));
  }, []);

  const guard = useCallback(async (work: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }, []);

  const selectedIds = useMemo(() => new Set(form.selected.map((t) => t.id)), [form.selected]);
  const blockedReason = localBlockReason(form);
  const requiredType = KIND_TARGET_TYPES[form.kind] ?? 'LISTING';

  const toggle = useCallback((target: WritebackTargetView) => {
    setForm((f) => {
      const has = f.selected.some((t) => t.id === target.id);
      const selected = has ? f.selected.filter((t) => t.id !== target.id) : [...f.selected, target];
      return { ...f, selected };
    });
  }, []);

  const onPreview = useCallback(async () => {
    if (blockedReason) {
      setError(blockedReason);
      return;
    }
    await guard(async () => {
      setPreview(await previewBatch(buildBatchInput(form)));
    });
  }, [blockedReason, form, guard]);

  const onExecute = useCallback(async () => {
    if (blockedReason) {
      setError(blockedReason);
      return;
    }
    await guard(async () => {
      const created = await executeBatch(buildBatchInput(form));
      setPreview(null);
      setDetail(created);
      // 执行完清空勾选与幂等键：留着不点第二次是安全的（幂等键命中会复用同一批次），
      // 但留着会让下一次真正的批量操作被上一次的内容静默吞掉。
      setForm(emptyBatchForm());
      await load();
    });
  }, [blockedReason, form, guard, load]);

  const register = useCallback(
    async (
      shopForForm: string,
      kind: 'LISTING' | 'SHIPMENT',
      externalRef: string,
      listingRef: string,
      skuCode: string,
      stock: string,
      priceValue: string,
      currency: string,
    ) => {
      await guard(async () => {
        // ⚠️ 填错的数量/价格**当场报错**，不悄悄当 null：填了却没登记上，会让运营以为
        // 快照里有这个值，而那正是批量操作前「从多少改成多少」的唯一依据。
        for (const [label, raw] of [
          ['库存', stock],
          ['价格', priceValue],
        ] as const) {
          if (raw.trim() && !Number.isFinite(Number(raw))) {
            throw new Error(`${label}必须是数字（收到「${raw.trim()}」）`);
          }
        }
        // 空串 → null：登记平台上「本来就没有」的值时不该发一个空字符串冒充真值。
        await upsertTarget({
          shopId: shopForForm,
          targetType: kind,
          externalRef,
          listingRef: listingRef || null,
          skuCode: skuCode || null,
          currentStock: stock.trim() ? Number(stock) : null,
          currentPrice: priceValue.trim() ? Number(priceValue) : null,
          currency: currency.trim().toUpperCase() || null,
          sourceRef: 'workspace',
        });
        await load();
      });
    },
    [guard, load],
  );

  const summaryRows = targetSummaryRows(pool?.summary);
  const totalPages = pool?.totalPages ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="常用回写操作"
        description="批量改库存 / 改价 / 下架 / 回传物流：生成 N 条独立回写任务，逐条记录结果。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      {/* 加粗句的页面级说明：它决定了这个页面**没有**什么（没有「一键重试整批」）。 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
        <p>
          批量操作不等于事务：每条回写独立成败，部分失败如实回报，不会因一条失败回滚全部
          （跨服务无全局事务）。含「结果未知」的批次**不要直接重试** —— {unknownHint}
        </p>
      </div>

      {error && (
        <SectionCard>
          <p className="text-danger text-sm" role="alert">
            {error}
          </p>
        </SectionCard>
      )}

      {/* 候选池汇总：真聚合，不受当前分页牵动 —— 否则「这一屏」会与「实际总量」对不上。 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {summaryRows.slice(0, 4).map((s) => (
          <StatCard key={s.label} label={s.label} value={s.value} polarity="neutral" />
        ))}
      </div>

      <SectionCard title="筛选与登记">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="mb-1 block">店铺</span>
            <select
              className="rounded border px-2 py-1"
              value={shopId}
              onChange={(e) => {
                setShopId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部店铺</option>
              {shops.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}（{s.platformCode}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">目标类型</span>
            <select
              className="rounded border px-2 py-1"
              value={targetType}
              onChange={(e) => {
                setTargetType(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              <option value="LISTING">平台商品</option>
              <option value="SHIPMENT">平台订单</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">状态</span>
            <select
              className="rounded border px-2 py-1"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              <option value="ACTIVE">可发起回写</option>
              <option value="BLOCKED">已封禁</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">平台键 / SKU 关键词</span>
            <Input
              value={keyword}
              placeholder="ASIN / SKU / 平台单号"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
        </div>
        <RegisterTargetForm shops={shops} onRegister={register} disabled={busy} />
      </SectionCard>

      {loading && !pool ? (
        <SectionCard>
          <p className="text-muted text-sm">加载中…</p>
        </SectionCard>
      ) : !pool || pool.items.length === 0 ? (
        <EmptyState
          title="还没有可回写目标"
          description="可回写目标由上游登记：04 刊登、07 库存、09 订单、10 物流推事件后落在这里；也可以在上方直接登记。"
        />
      ) : (
        <SectionCard title={`可回写目标（共 ${pool.totalElements} 条）`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">选择</th>
                <th className="p-2">平台键 / SKU</th>
                <th className="p-2">店铺</th>
                <th className="p-2">类型</th>
                <th className="p-2">当前库存 / 价格</th>
                <th className="p-2">状态</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {pool.items.map((t) => (
                <tr key={t.id} className="border-b">
                  <td className="p-2">
                    <input
                      type="checkbox"
                      aria-label={`选择 ${t.externalRef}`}
                      checked={selectedIds.has(t.id)}
                      onChange={() => toggle(t)}
                    />
                  </td>
                  <td className="p-2">
                    <div className="font-mono text-xs">{t.externalRef}</div>
                    <div className="text-muted text-xs">{t.skuCode || '—'}</div>
                  </td>
                  <td className="p-2 text-xs">
                    {t.shopName}
                    <div className="text-muted">{t.platformCode}</div>
                  </td>
                  <td className="p-2 text-xs">
                    {TARGET_TYPE_LABELS[t.targetType] ?? t.targetType}
                  </td>
                  <td className="p-2 text-xs">
                    <div>库存 {quantity(t.currentStock)}</div>
                    <div>{price(t.currentPrice, t.currency)}</div>
                  </td>
                  <td className="p-2">
                    <Badge tone={t.status === 'ACTIVE' ? 'info' : 'neutral'}>
                      {t.status === 'ACTIVE' ? '可发起回写' : '已封禁'}
                    </Badge>
                    {t.blockedReason && <div className="text-muted text-xs">{t.blockedReason}</div>}
                  </td>
                  <td className="p-2">
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        void guard(async () => {
                          if (t.status === 'ACTIVE') {
                            // 封禁必须写原因：后端会拒，界面上先问一句，别让一次点击撞 400。
                            const reason = window.prompt('封禁原因（批量时会逐条拦下这一条）', '');
                            if (reason === null) return;
                            if (!reason.trim()) {
                              setError('封禁必须写明原因');
                              return;
                            }
                            await setTargetStatus(t.id, { status: 'BLOCKED', reason });
                          } else {
                            await setTargetStatus(t.id, { status: 'ACTIVE', reason: '' });
                          }
                          await load();
                        })
                      }
                    >
                      {t.status === 'ACTIVE' ? '封禁' : '解封'}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted">
              第 {pool.page + 1} 页 · 每页 {pool.size} · 共 {pool.totalElements} 条
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                disabled={pool.page <= 0 || loading}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                disabled={pool.page + 1 >= totalPages || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      <BatchPanel
        form={form}
        patch={patch}
        preview={preview}
        blockedReason={blockedReason}
        requiredType={requiredType}
        busy={busy}
        onPreview={onPreview}
        onExecute={onExecute}
        onClearSelection={() => patch({ selected: [] })}
      />

      <SectionCard title="批次与逐条结果">
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="mb-1 block">结局</span>
            <select
              className="rounded border px-2 py-1"
              value={batchOutcome}
              onChange={(e) => setBatchOutcome(e.target.value)}
            >
              <option value="">全部</option>
              <option value="IN_PROGRESS">进行中</option>
              <option value="PARTIAL">部分成功</option>
              <option value="SUCCEEDED">全部成功</option>
              <option value="FAILED">全部失败</option>
            </select>
          </label>
        </div>
        {batches.length === 0 ? (
          <EmptyState
            title="还没有批量回写记录"
            description="在上方勾选目标或给出筛选条件后执行一次，这里会出现批次与逐条结果。"
          />
        ) : (
          <div className="space-y-2">
            {batches.map((b) => (
              <div
                key={b.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded border px-3 py-2 text-sm"
              >
                <div>
                  <div className="font-mono text-xs">{b.batchNo}</div>
                  <div className="text-muted text-xs">{batchLine(b)}</div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={outcomeTone(b.summary.outcome)}>
                    {OUTCOME_LABELS[b.summary.outcome] ?? b.summary.outcome}
                  </Badge>
                  <Button
                    variant="ghost"
                    onClick={() =>
                      void guard(async () => {
                        setDetail(await fetchBatch(b.id));
                      })
                    }
                  >
                    逐条结果
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {detail && <DetailPanel batch={detail} />}
    </div>
  );
}

/** 登记一个可回写目标。上游服务推事件后也会落在这里，页面登记是运营侧的手工入口。 */
function RegisterTargetForm({
  shops,
  onRegister,
  disabled,
}: {
  shops: ShopView[];
  onRegister: (
    shopId: string,
    kind: 'LISTING' | 'SHIPMENT',
    externalRef: string,
    listingRef: string,
    skuCode: string,
    stock: string,
    price: string,
    currency: string,
  ) => Promise<void>;
  disabled: boolean;
}) {
  const [shopForForm, setShopForForm] = useState('');
  const [kind, setKind] = useState<'LISTING' | 'SHIPMENT'>('LISTING');
  const [externalRef, setExternalRef] = useState('');
  const [listingRef, setListingRef] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [stock, setStock] = useState('');
  const [priceValue, setPriceValue] = useState('');
  const [currency, setCurrency] = useState('');

  return (
    <div className="mt-4 flex flex-wrap items-end gap-3 border-t pt-4">
      <label className="text-sm">
        <span className="mb-1 block">登记到店铺</span>
        <select
          className="rounded border px-2 py-1"
          value={shopForForm}
          onChange={(e) => setShopForForm(e.target.value)}
        >
          <option value="">选择店铺…</option>
          {shops.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      <label className="text-sm">
        <span className="mb-1 block">目标类型</span>
        <select
          className="rounded border px-2 py-1"
          value={kind}
          onChange={(e) => setKind(e.target.value as 'LISTING' | 'SHIPMENT')}
        >
          <option value="LISTING">平台商品</option>
          <option value="SHIPMENT">平台订单</option>
        </select>
      </label>
      <label className="text-sm">
        <span className="mb-1 block">平台键（必填）</span>
        <Input value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">刊登号</span>
        <Input value={listingRef} onChange={(e) => setListingRef(e.target.value)} />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">SKU 码</span>
        <Input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} />
      </label>
      {kind === 'LISTING' && (
        <>
          <label className="text-sm">
            <span className="mb-1 block">当前库存</span>
            <Input value={stock} placeholder="10.000" onChange={(e) => setStock(e.target.value)} />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">当前价格</span>
            <Input
              value={priceValue}
              placeholder="19.9900"
              onChange={(e) => setPriceValue(e.target.value)}
            />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">币种</span>
            <Input
              value={currency}
              placeholder="USD"
              maxLength={3}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
          </label>
        </>
      )}
      <Button
        disabled={disabled || !shopForForm || !externalRef.trim()}
        onClick={() => {
          void onRegister(
            shopForForm,
            kind,
            externalRef.trim(),
            listingRef.trim(),
            skuCode.trim(),
            stock,
            priceValue,
            currency,
          );
          setExternalRef('');
          setListingRef('');
          setSkuCode('');
          setStock('');
          setPriceValue('');
          setCurrency('');
        }}
      >
        登记目标
      </Button>
    </div>
  );
}

/** 批量操作面板：选动作 → 填参数 → 试算 → 执行。 */
function BatchPanel({
  form,
  patch,
  preview,
  blockedReason,
  requiredType,
  busy,
  onPreview,
  onExecute,
  onClearSelection,
}: {
  form: BatchForm;
  patch: (next: Partial<BatchForm>) => void;
  preview: WritebackBatchPreview | null;
  blockedReason: string | null;
  requiredType: 'LISTING' | 'SHIPMENT';
  busy: boolean;
  onPreview: () => Promise<void>;
  onExecute: () => Promise<void>;
  onClearSelection: () => void;
}) {
  const isShip = form.kind === 'SHIP_UPDATE';
  const isPrice = form.kind === 'UPDATE_PRICE';

  return (
    <SectionCard title="批量回写">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="mb-1 block">动作</span>
          <select
            className="rounded border px-2 py-1"
            value={form.kind}
            onChange={(e) => patch({ kind: e.target.value as BatchForm['kind'] })}
          >
            {Object.entries(KIND_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block">限定店铺（可选）</span>
          <Input
            value={form.shopId ?? ''}
            placeholder="留空 = 按筛选条件跨店铺"
            onChange={(e) => patch({ shopId: e.target.value || null })}
          />
        </label>
        {form.kind === 'UPDATE_STOCK' && (
          <label className="text-sm">
            <span className="mb-1 block">库存数量</span>
            <Input
              value={form.stock}
              placeholder="42.500"
              onChange={(e) => patch({ stock: e.target.value })}
            />
          </label>
        )}
        {isPrice && (
          <>
            <label className="text-sm">
              <span className="mb-1 block">价格</span>
              <Input
                value={form.price}
                placeholder="25.0000"
                onChange={(e) => patch({ price: e.target.value })}
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">币种</span>
              <Input
                value={form.currency}
                placeholder="USD"
                maxLength={3}
                onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
              />
            </label>
          </>
        )}
        {isShip && (
          <>
            <label className="text-sm">
              <span className="mb-1 block">公共运单号</span>
              <Input
                value={form.trackingNumber}
                placeholder="逐票不同时在下方逐条覆盖"
                onChange={(e) => patch({ trackingNumber: e.target.value })}
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">公共承运商</span>
              <Input
                value={form.carrierCode}
                placeholder="SF"
                onChange={(e) => patch({ carrierCode: e.target.value })}
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">发货时刻</span>
              <Input
                type="datetime-local"
                value={form.shippedAt}
                onChange={(e) => patch({ shippedAt: e.target.value })}
              />
            </label>
          </>
        )}
        <label className="text-sm">
          <span className="mb-1 block">业务幂等键（可选）</span>
          <Input
            value={form.idempotencyKey}
            placeholder="留空 = 按请求内容去重"
            onChange={(e) => patch({ idempotencyKey: e.target.value })}
          />
        </label>
        <label className="text-sm">
          <span className="mb-1 block">操作说明</span>
          <Input
            value={form.reason}
            placeholder="清退季节品"
            onChange={(e) => patch({ reason: e.target.value })}
          />
        </label>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
        <span className="text-muted">{batchScopeText(form)}</span>
        <span className="text-muted">仅对「{TARGET_TYPE_LABELS[requiredType]}」生效</span>
        {form.selected.length > 0 && (
          <Button variant="ghost" onClick={onClearSelection}>
            清空勾选
          </Button>
        )}
        <Button variant="secondary" disabled={busy} onClick={() => void onPreview()}>
          试算
        </Button>
        <Button disabled={busy || blockedReason !== null} onClick={() => void onExecute()}>
          执行批量回写
        </Button>
        {blockedReason && (
          <span className="text-danger" role="alert">
            {blockedReason}
          </span>
        )}
        {form.selected.length > BATCH_HARD_LIMIT && (
          <span className="text-danger">已超过单次 {BATCH_HARD_LIMIT} 条上限</span>
        )}
      </div>

      {preview && (
        <div className="mt-4 rounded border p-3 text-sm">
          <div className="mb-2 font-medium">{previewHeadline(preview)}</div>
          {preview.issues.length === 0 ? (
            <p className="text-muted">
              没有会被拦下的目标 —— 执行后每一条都会生成一条独立的平台回写任务。
            </p>
          ) : (
            <ul className="space-y-1">
              {preview.issues.slice(0, 20).map((issue, i) => (
                <li key={`${issue.targetId ?? 'x'}-${i}`} className="flex items-start gap-2">
                  <Badge tone={issueTone(issue)}>{issueLabel(issue)}</Badge>
                  <span className="font-mono text-xs">
                    {issue.externalRef || '（目标已不存在）'}
                  </span>
                  <span className="text-muted text-xs">{issue.message}</span>
                </li>
              ))}
              {preview.issues.length > 20 && (
                <li className="text-muted text-xs">
                  另有 {preview.issues.length - 20} 条同类问题未列出。
                </li>
              )}
            </ul>
          )}
        </div>
      )}

      {/* 逐目标覆盖：批量回传物流时每票货的运单号本就各不相同 —— 只给一个公共运单号等于
          逼着运营分 N 次批量，而 N 次批量之间还可能漏勾（多选行才出现，只对勾选的范围有意义）。 */}
      {form.selected.length > 0 && (isShip || form.kind === 'UPDATE_STOCK' || isPrice) && (
        <PerTargetEditor
          form={form}
          patch={patch}
          showShip={isShip}
          showStock={form.kind === 'UPDATE_STOCK'}
          showPrice={isPrice}
        />
      )}
    </SectionCard>
  );
}

/** 逐目标覆盖编辑器：只列出**已勾选**的目标，避免对着整个候选池渲染几百行输入框。 */
function PerTargetEditor({
  form,
  patch,
  showShip,
  showStock,
  showPrice,
}: {
  form: BatchForm;
  patch: (next: Partial<BatchForm>) => void;
  showShip: boolean;
  showStock: boolean;
  showPrice: boolean;
}) {
  const setOverride = (id: string, next: Partial<PerTargetForm>) => {
    patch({
      perTarget: {
        ...form.perTarget,
        [id]: { ...(form.perTarget[id] ?? emptyPerTargetForm()), ...next },
      },
    });
  };

  return (
    <div className="mt-4 rounded border p-3">
      <div className="mb-2 text-sm font-medium">逐目标覆盖（留空 = 沿用上面的公共值）</div>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left">
            <th className="p-2">平台键</th>
            {showStock && <th className="p-2">库存</th>}
            {showPrice && <th className="p-2">价格</th>}
            {showShip && <th className="p-2">运单号</th>}
            {showShip && <th className="p-2">承运商</th>}
          </tr>
        </thead>
        <tbody>
          {form.selected.map((t) => {
            const o = form.perTarget[t.id] ?? emptyPerTargetForm();
            return (
              <tr key={t.id} className="border-b">
                <td className="p-2 font-mono text-xs">{t.externalRef}</td>
                {showStock && (
                  <td className="p-2">
                    <Input
                      value={o.stock}
                      placeholder={form.stock || '—'}
                      onChange={(e) => setOverride(t.id, { stock: e.target.value })}
                    />
                  </td>
                )}
                {showPrice && (
                  <td className="p-2">
                    <Input
                      value={o.price}
                      placeholder={form.price || '—'}
                      onChange={(e) => setOverride(t.id, { price: e.target.value })}
                    />
                  </td>
                )}
                {showShip && (
                  <>
                    <td className="p-2">
                      <Input
                        value={o.trackingNumber}
                        placeholder={form.trackingNumber || '—'}
                        onChange={(e) => setOverride(t.id, { trackingNumber: e.target.value })}
                      />
                    </td>
                    <td className="p-2">
                      <Input
                        value={o.carrierCode}
                        placeholder={form.carrierCode || '—'}
                        onChange={(e) => setOverride(t.id, { carrierCode: e.target.value })}
                      />
                    </td>
                  </>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** 批次详情：逐条结果。这里是「部分失败不影响其余」唯一说得清的地方。 */
function DetailPanel({ batch }: { batch: WritebackBatchView }) {
  const s = batch.summary;
  return (
    <SectionCard title={`批次 ${batch.batchNo} · 逐条结果`}>
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <Badge tone={outcomeTone(s.outcome)}>{OUTCOME_LABELS[s.outcome] ?? s.outcome}</Badge>
        <span className="text-muted">
          {KIND_LABELS[batch.kind] ?? batch.kind} · 选中 {s.selected} · 已发出 {s.queued} · 没发出去{' '}
          {s.rejected} · 平台成功 {s.succeeded} · 平台失败 {s.failed} · 结果未知 {s.unknown} ·
          进行中 {s.pending}
        </span>
      </div>
      {s.unknown > 0 && (
        <div className="mb-3 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
          <p>{unknownHint}</p>
        </div>
      )}
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left">
            <th className="p-2">#</th>
            <th className="p-2">目标</th>
            <th className="p-2">结果</th>
            <th className="p-2">内容</th>
            <th className="p-2">说明</th>
          </tr>
        </thead>
        <tbody>
          {batch.items.map((item) => (
            <tr key={item.position} className="border-b">
              <td className="p-2 text-xs">{item.position}</td>
              <td className="p-2">
                <div className="font-mono text-xs">{item.externalRef || '（目标已不存在）'}</div>
                <div className="text-muted text-xs">
                  {TARGET_TYPE_LABELS[item.targetType] ?? item.targetType ?? '—'}
                </div>
              </td>
              <td className="p-2">
                <Badge tone={itemTone(item)}>{itemResultText(item)}</Badge>
              </td>
              <td className="p-2 text-xs">
                {item.itemResult === 'REJECTED' ? (
                  <span className="text-muted">未发出</span>
                ) : (
                  <>
                    {item.stock !== null && <div>库存 {quantity(item.stock)}</div>}
                    {item.price !== null && <div>{price(item.price, item.currency)}</div>}
                    {item.trackingNumber && (
                      <div>
                        {item.carrierCode} {item.trackingNumber}
                      </div>
                    )}
                    {item.shippedAt && (
                      <div className="text-muted">
                        发货 {new Date(item.shippedAt).toLocaleString('zh-CN')}
                      </div>
                    )}
                  </>
                )}
              </td>
              <td className="p-2 text-xs">
                {item.reason || '—'}
                {item.idempotencyKey && (
                  <div className="text-muted font-mono">
                    幂等键 {item.idempotencyKey.slice(0, 16)}…
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </SectionCard>
  );
}
