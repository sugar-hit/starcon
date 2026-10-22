'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  batchCreateInfluencerOutreach,
  batchResultLabel,
  commissionScopeLabel,
  commissionTypeLabel,
  configStatusLabel,
  createInfluencerCommissionAccrual,
  createInfluencerOutreachFollowup,
  createInfluencerSampleOrder,
  createInfluencerSampleShipment,
  deleteInfluencerCommissionRule,
  deleteInfluencerOutreach,
  deleteInfluencerOutreachSegment,
  deleteInfluencerOutreachTemplate,
  emptyInfluencerCommissionAccrualInput,
  emptyInfluencerCommissionRuleInput,
  emptyInfluencerOutreachInput,
  emptyInfluencerOutreachPolicyInput,
  emptyInfluencerOutreachSegmentInput,
  emptyInfluencerOutreachTemplateInput,
  emptyInfluencerSampleOrderInput,
  fetchInfluencerCommissionAccruals,
  fetchInfluencerCommissionRules,
  fetchInfluencerOutreach,
  fetchInfluencerOutreachAvailability,
  fetchInfluencerOutreachCandidates,
  fetchInfluencerOutreachCapabilities,
  fetchInfluencerOutreachFollowups,
  fetchInfluencerOutreachOptions,
  fetchInfluencerOutreachPolicies,
  fetchInfluencerOutreachSegments,
  fetchInfluencerOutreachSummary,
  fetchInfluencerOutreachTemplatePreview,
  fetchInfluencerOutreachTemplates,
  fetchInfluencerOutreachs,
  fetchInfluencerSampleOrders,
  fetchInfluencerSampleShipments,
  financeText,
  followupTypeLabel,
  outreachCount,
  outreachMoney,
  outreachRatio,
  outreachStatusLabel,
  outboundText,
  registerInfluencerOutreachReceipt,
  sampleOrderStatusLabel,
  saveInfluencerCommissionRule,
  saveInfluencerOutreach,
  saveInfluencerOutreachPolicy,
  saveInfluencerOutreachSegment,
  saveInfluencerOutreachTemplate,
  sendInfluencerOutreach,
  shipmentStatusLabel,
  transitionInfluencerOutreach,
  transitionInfluencerSampleOrder,
  transitionInfluencerSampleShipment,
  updateInfluencerCommissionRule,
  updateInfluencerOutreach,
  updateInfluencerOutreachSegment,
  updateInfluencerOutreachTemplate,
  updateInfluencerSampleOrder,
  validateInfluencerCommissionRule,
  validateInfluencerOutreach,
  validateInfluencerOutreachSegment,
  validateInfluencerOutreachTemplate,
  validateInfluencerSampleOrder,
} from './influencer-outreach-model';
import type {
  InfluencerCommissionAccrualInput,
  InfluencerCommissionAccrualPage,
  InfluencerCommissionRuleInput,
  InfluencerCommissionRulePage,
  InfluencerOutreachBatchResult,
  InfluencerOutreachCandidatePage,
  InfluencerOutreachCapabilities,
  InfluencerOutreachDetail,
  InfluencerOutreachInput,
  InfluencerOutreachOptions,
  InfluencerOutreachPage,
  InfluencerOutreachPolicyInput,
  InfluencerOutreachPolicyPage,
  InfluencerOutreachQuery,
  InfluencerOutreachSegmentInput,
  InfluencerOutreachSegmentPage,
  InfluencerOutreachTemplateInput,
  InfluencerOutreachTemplatePage,
  InfluencerOutreachTemplatePreview,
  InfluencerOutreachSummary,
  InfluencerSampleOrderInput,
  InfluencerSampleOrderPage,
  InfluencerSampleShipmentPage,
} from './influencer-outreach-model';

/**
 * 14.5.1.2 建联与样品。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **建联必须走官方接口**。平台达人私信官方接口未交付 ⇒ 发送入口明确返回
 *    「未接入」并**不写任何数据**；页面**没有「批量群发」按钮**，批量只到
 *    「按规则批量生成**草稿**」，每一条仍要**逐条**登记平台回执才真正发出。
 * 2. **频率上限是配置事实**。每日发送上限 + 最小间隔秒数，超限直接拒绝并说清原因。
 * 3. **下游缺口必须看得见**。8 仓库（出库）/ 10 物流（轨迹）/ 12 财务（入账）
 *    三个下游在本仓基线里都不可调用 ⇒ 页面上分别显示「本地已记出库」、
 *    「轨迹不可查（归 10 物流 管）」、「未进费用归集（归 12 财务 管）」，
 *    而不是显示一个假状态。
 * 4. **样品签收不是人填的**。签收只能由包裹推导，页面手工推进只到「已寄出 / 已取消」。
 * 5. **「没录」必须看得见**。样品成本 / 佣金比例 / 计提基数没录显示「未录入」，
 *    **不是 0.00** —— 0 会被读成「这个达人一件样品成本都没有」。
 */
export function InfluencerOutreachWorkspace() {
  const [capabilities, setCapabilities] = useState<InfluencerOutreachCapabilities | null>(null);
  const [options, setOptions] = useState<InfluencerOutreachOptions | null>(null);
  const [summary, setSummary] = useState<InfluencerOutreachSummary | null>(null);
  const [tab, setTab] = useState<
    'outreach' | 'segments' | 'templates' | 'samples' | 'commission'
  >('outreach');
  const [error, setError] = useState('');

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await fetchInfluencerOutreachSummary());
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchInfluencerOutreachCapabilities(),
          fetchInfluencerOutreachOptions(),
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary, tab]);

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium">建联与样品</h1>
        <p className="text-muted-foreground text-sm">
          14.5.1.2 · 按规则筛选达人 → 模板化私信邀约（走官方接口）→ 寄样管理（样品单 / 物流 /
          签收）→ 合作跟进 → 佣金设置。
        </p>
      </header>

      {capabilities ? (
        <Section title="能力声明（未交付段显式标注）">
          <ul className="flex flex-col gap-1 text-sm">
            {capabilities.dimensions.map((dim) => (
              <li key={dim.dimension} className="flex flex-col">
                <span className={dim.available ? 'text-emerald-600' : 'text-amber-600'}>
                  {dim.available ? '✓' : '✗'} {dim.description}
                </span>
                {!dim.available ? (
                  <span className="text-muted-foreground text-xs">
                    责任模块 {dim.ownerModule}：{dim.impact}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground text-xs">{capabilities.officialApiNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.rateLimitNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.downstreamNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.upstreamNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : null}

      {summary ? (
        <Section title="概览">
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="建联记录" value={String(summary.totalOutreachs)} />
            <Stat label="草稿" value={String(summary.draftCount)} />
            <Stat label="已发送" value={String(summary.sentCount)} />
            <Stat label="已回复" value={String(summary.repliedCount)} />
            <Stat label="合作中" value={String(summary.cooperatingCount)} />
            <Stat label="已完成" value={String(summary.completedCount)} />
            <Stat label="已拒绝 / 无回复 / 已取消" value={String(summary.closedCount)} />
            <Stat label="样品单" value={String(summary.sampleOrderCount)} />
            <Stat label="已签收样品单" value={String(summary.signedSampleCount)} />
            <Stat label="待签收样品单" value={String(summary.pendingSignCount)} />
            <Stat label="佣金计提条数" value={String(summary.accrualCount)} />
            <Stat
              label="本地计提金额合计"
              value={outreachMoney(summary.accruedAmountTotal, summary.accruedCurrency)}
            />
          </dl>
          <p className="text-muted-foreground text-xs">{summary.downstreamNote}</p>
        </Section>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <TabButton active={tab === 'outreach'} onClick={() => setTab('outreach')}>
          建联记录
        </TabButton>
        <TabButton active={tab === 'segments'} onClick={() => setTab('segments')}>
          筛选条件
        </TabButton>
        <TabButton active={tab === 'templates'} onClick={() => setTab('templates')}>
          邀约模板
        </TabButton>
        <TabButton active={tab === 'samples'} onClick={() => setTab('samples')}>
          样品与物流
        </TabButton>
        <TabButton active={tab === 'commission'} onClick={() => setTab('commission')}>
          佣金
        </TabButton>
      </div>

      {tab === 'outreach' ? <OutreachTab options={options} onChanged={loadSummary} /> : null}
      {tab === 'segments' ? <SegmentsTab options={options} /> : null}
      {tab === 'templates' ? <TemplatesTab options={options} /> : null}
      {tab === 'samples' ? <SamplesTab options={options} onChanged={loadSummary} /> : null}
      {tab === 'commission' ? <CommissionTab options={options} onChanged={loadSummary} /> : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </main>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      className={`rounded border px-3 py-1 text-sm ${active ? 'bg-primary text-primary-foreground' : ''}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

// ==================================================================
// 建联记录
// ==================================================================

function OutreachTab({
  options,
  onChanged,
}: {
  options: InfluencerOutreachOptions | null;
  onChanged: () => Promise<void>;
}) {
  const [query, setQuery] = useState<InfluencerOutreachQuery>({
    sort: 'CREATED_DESC',
    page: 0,
    size: 20,
  });
  const [page, setPage] = useState<InfluencerOutreachPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<InfluencerOutreachDetail | null>(null);
  const [form, setForm] = useState<InfluencerOutreachInput | null>(null);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [receipt, setReceipt] = useState({ platformMessageId: '', platformReceipt: '' });
  const [batch, setBatch] = useState({ segmentId: '', templateId: '', batchKey: '' });
  const [batchResult, setBatchResult] = useState<InfluencerOutreachBatchResult | null>(null);
  const [availability, setAvailability] = useState<
    Awaited<ReturnType<typeof fetchInfluencerOutreachAvailability>> | null
  >(null);

  const load = useCallback(async (next: InfluencerOutreachQuery) => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencerOutreachs(next));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(query);
  }, [load, query]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const result = await fetchInfluencerOutreachAvailability();
        if (alive) setAvailability(result);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function applyFilter(patch: Partial<InfluencerOutreachQuery>) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  async function openDetail(id: string) {
    setError('');
    try {
      setDetail(await fetchInfluencerOutreach(id));
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitForm() {
    if (!form) return;
    const check = validateInfluencerOutreach(form);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setError('');
    try {
      if (editingId) {
        await updateInfluencerOutreach(editingId, form);
      } else {
        await saveInfluencerOutreach(form);
      }
      setForm(null);
      setEditingId(null);
      await load(query);
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  /** 🔴 手动登记「已发送 + 平台回执」：官方私信接口未交付时唯一的发送登记入口。 */
  async function submitReceipt() {
    if (!detail) return;
    if (!receipt.platformMessageId.trim()) {
      setError('平台消息 id 必填');
      return;
    }
    setError('');
    try {
      await registerInfluencerOutreachReceipt(detail.outreach.id, {
        platformMessageId: receipt.platformMessageId,
        platformReceipt: receipt.platformReceipt,
      });
      setReceipt({ platformMessageId: '', platformReceipt: '' });
      await openDetail(detail.outreach.id);
      await load(query);
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  /** 🔴 走官方接口发送：未接入 ⇒ 明确失败且**不写任何数据**。 */
  async function sendViaPlatform() {
    if (!detail) return;
    setError('');
    try {
      const result = await sendInfluencerOutreach(detail.outreach.id);
      setError(result.message);
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function transition(status: string) {
    if (!detail) return;
    setError('');
    try {
      await transitionInfluencerOutreach(detail.outreach.id, { status });
      await openDetail(detail.outreach.id);
      await load(query);
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function remove(id: string) {
    setError('');
    try {
      await deleteInfluencerOutreach(id);
      setDetail(null);
      await load(query);
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  /** 🔴 批量建联：**只生成草稿**（不是批量群发）。 */
  async function submitBatch() {
    if (!batch.segmentId || !batch.templateId) {
      setError('批量建联要选筛选条件与模板');
      return;
    }
    setError('');
    try {
      const result = await batchCreateInfluencerOutreach({
        segmentId: batch.segmentId,
        templateId: batch.templateId,
        batchKey: batch.batchKey || undefined,
      });
      setBatchResult(result);
      await load(query);
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section title="建联记录（真分页 + 真筛选 + 真排序）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="状态">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.status ?? ''}
              onChange={(e) => applyFilter({ status: e.target.value })}
            >
              <option value="">全部状态</option>
              {(options?.outreachStatuses ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="平台">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.platformCode ?? ''}
              onChange={(e) => applyFilter({ platformCode: e.target.value })}
            >
              <option value="">全部平台</option>
              {(options?.platformCodes ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.shopCode ?? ''}
              onChange={(e) => applyFilter({ shopCode: e.target.value })}
            >
              <option value="">全部店铺</option>
              {(options?.shopCodes ?? []).map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键字">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.keyword ?? ''}
              onChange={(e) => applyFilter({ keyword: e.target.value })}
            />
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? 'CREATED_DESC'}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              <option value="CREATED_DESC">最近创建</option>
              <option value="SENT_DESC">最近发送</option>
              <option value="SENT_ASC">最早发送</option>
              <option value="STATUS_ASC">按状态</option>
              <option value="INFLUENCER_ASC">按达人名</option>
            </select>
          </Field>
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() =>
              setForm(emptyInfluencerOutreachInput())
            }
          >
            新建建联记录
          </button>
        </div>

        {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
        {!loading && page && page.items.length === 0 ? (
          <EmptyState
            hasFilter={Boolean(
              query.status || query.platformCode || query.shopCode || query.keyword,
            )}
            onReset={() =>
              setQuery({ sort: 'CREATED_DESC', page: 0, size: 20 })
            }
          />
        ) : null}
        {page && page.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">达人</th>
                  <th className="py-1">分群 / 模板</th>
                  <th className="py-1">平台 / 店铺</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">发送时刻</th>
                  <th className="py-1">跟进 / 样品 / 计提</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">
                      {row.influencerName}
                      <span className="text-muted-foreground text-xs">（{row.influencerCategory}）</span>
                    </td>
                    <td className="py-1">
                      {row.segmentName} / {row.templateName}
                    </td>
                    <td className="py-1">
                      {row.platformCode} / {row.shopCode}
                    </td>
                    <td className="py-1">{outreachStatusLabel(row.status)}</td>
                    <td className="py-1">{row.sentAt ? new Date(row.sentAt).toLocaleString('zh-CN') : '—'}</td>
                    <td className="py-1">
                      {row.followupCount} / {row.sampleOrderCount} / {row.accrualCount}
                    </td>
                    <td className="flex gap-2 py-1">
                      <button className="rounded border px-2 py-1 text-xs" onClick={() => void openDetail(row.id)}>
                        详情
                      </button>
                      {row.status === 'DRAFT' ? (
                        <>
                          <button
                            className="rounded border px-2 py-1 text-xs"
                            onClick={() => {
                              setEditingId(row.id);
                              setForm({
                                influencerId: row.influencerId,
                                segmentId: row.segmentId,
                                templateId: row.templateId,
                                idempotencyKey: row.idempotencyKey,
                                note: row.note,
                              });
                            }}
                          >
                            编辑
                          </button>
                          <button
                            className="rounded border px-2 py-1 text-xs"
                            onClick={() => void remove(row.id)}
                          >
                            删除
                          </button>
                        </>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {page ? (
          <Pagination
            page={page.page}
            size={page.size}
            total={page.total}
            onChange={(p) => setQuery((prev) => ({ ...prev, page: p }))}
          />
        ) : null}
      </Section>

      {form ? (
        <Section title={editingId ? '改建联记录（只有草稿可改）' : '新建建联记录（草稿）'}>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="达人 id">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.influencerId}
                onChange={(e) => setForm({ ...form, influencerId: e.target.value })}
              />
            </Field>
            <Field label="筛选条件 id">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.segmentId}
                onChange={(e) => setForm({ ...form, segmentId: e.target.value })}
              />
            </Field>
            <Field label="模板 id">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.templateId}
                onChange={(e) => setForm({ ...form, templateId: e.target.value })}
              />
            </Field>
            <Field label="幂等键">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.idempotencyKey}
                onChange={(e) => setForm({ ...form, idempotencyKey: e.target.value })}
              />
            </Field>
            <Field label="备注">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
              />
            </Field>
            <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitForm()}>
              保存
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setForm(null);
                setEditingId(null);
                setFormErrors([]);
              }}
            >
              取消
            </button>
          </div>
          <FormErrors errors={formErrors} />
          <p className="text-muted-foreground text-xs">
            🔴 幂等键必填：同一条发送重复登记不会发第二遍。建联记录的沟通渠道由服务端钉死为
            <strong>OFFICIAL_API</strong>（建联必须走官方接口）。
          </p>
        </Section>
      ) : null}

      <Section title="批量建联（🔴 只生成草稿，不是批量群发）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="筛选条件 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={batch.segmentId}
              onChange={(e) => setBatch({ ...batch, segmentId: e.target.value })}
            />
          </Field>
          <Field label="模板 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={batch.templateId}
              onChange={(e) => setBatch({ ...batch, templateId: e.target.value })}
            />
          </Field>
          <Field label="批次键（幂等）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={batch.batchKey}
              onChange={(e) => setBatch({ ...batch, batchKey: e.target.value })}
            />
          </Field>
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitBatch()}>
            按规则批量生成草稿
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 批量只做到「生成草稿」：每一条仍要**逐条**登记平台回执才会真正发出，
          登记时过幂等键 + 每日发送上限 + 最小间隔三道闸 —— 批量私信是平台风控重点。
        </p>
        {batchResult ? (
          <>
            <p className="text-sm">
              筛中 {batchResult.matchedCount} 人 · 新建 {batchResult.createdCount} 条 · 跳过{' '}
              {batchResult.skippedCount} 条
            </p>
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">达人</th>
                    <th className="py-1">结果</th>
                    <th className="py-1">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {batchResult.items.map((item) => (
                    <tr key={item.influencerId} className="border-t">
                      <td className="py-1">{item.influencerName}</td>
                      <td className="py-1">{batchResultLabel(item.result)}</td>
                      <td className="text-muted-foreground py-1 text-xs">{item.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : null}
      </Section>

      {availability ? (
        <Section title="平台达人私信官方接口（接入情况）">
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">平台</th>
                  <th className="py-1">接入状态</th>
                  <th className="py-1">今日已登记发送</th>
                  <th className="py-1">每日上限</th>
                  <th className="py-1">原因</th>
                </tr>
              </thead>
              <tbody>
                {availability.platforms.map((p) => (
                  <tr key={p.platformCode} className="border-t">
                    <td className="py-1">
                      {p.platformName}（{p.platformCode}）
                    </td>
                    <td className={`py-1 ${p.available ? 'text-emerald-600' : 'text-amber-600'}`}>
                      {p.available ? '已接入' : '未接入'}
                    </td>
                    <td className="py-1">{p.sentToday}</td>
                    <td className="py-1">{p.dailySendLimit}</td>
                    <td className="text-muted-foreground py-1 text-xs">{p.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      ) : null}

      {detail ? (
        <Section title="建联记录详情">
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="达人" value={detail.outreach.influencerName} />
            <Stat label="状态" value={outreachStatusLabel(detail.outreach.status)} />
            <Stat label="沟通渠道" value={detail.outreach.contactChannel} />
            <Stat label="幂等键" value={detail.outreach.idempotencyKey} />
            <Stat label="平台消息 id" value={detail.outreach.platformMessageId || '—'} />
            <Stat label="发送时刻" value={detail.outreach.sentAt ?? '—'} />
          </dl>
          <div className="flex flex-col gap-1">
            <span className="text-muted-foreground text-xs">标题（渲染快照）</span>
            <p className="text-sm">{detail.outreach.subject || '—'}</p>
            <span className="text-muted-foreground text-xs">正文（渲染快照）</span>
            <pre className="text-sm whitespace-pre-wrap">{detail.outreach.messageBody}</pre>
            {detail.outreach.platformReceipt ? (
              <>
                <span className="text-muted-foreground text-xs">平台回执</span>
                <p className="text-sm whitespace-pre-wrap">{detail.outreach.platformReceipt}</p>
              </>
            ) : null}
          </div>

          {detail.outreach.status === 'DRAFT' ? (
            <>
              <div className="flex flex-wrap items-end gap-2">
                <Field label="平台消息 id">
                  <input
                    className="rounded border px-2 py-1 text-sm"
                    value={receipt.platformMessageId}
                    onChange={(e) => setReceipt({ ...receipt, platformMessageId: e.target.value })}
                  />
                </Field>
                <Field label="平台回执">
                  <input
                    className="rounded border px-2 py-1 text-sm"
                    value={receipt.platformReceipt}
                    onChange={(e) => setReceipt({ ...receipt, platformReceipt: e.target.value })}
                  />
                </Field>
                <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitReceipt()}>
                  登记已发送 + 平台回执
                </button>
                <button className="rounded border px-3 py-1 text-sm" onClick={() => void sendViaPlatform()}>
                  走官方接口发送
                </button>
                <button
                  className="rounded border px-3 py-1 text-sm"
                  onClick={() => void transition('CANCELLED')}
                >
                  取消这条邀约
                </button>
              </div>
              <p className="text-muted-foreground text-xs">
                🔴 官方私信接口未交付 ⇒「走官方接口发送」会明确返回「未接入」并且
                <strong>不写任何数据</strong>。已交付的那一半是「手动登记已发送 + 平台回执」。
              </p>
            </>
          ) : null}

          {detail.outreach.status !== 'DRAFT' ? (
            <div className="flex flex-wrap items-end gap-2">
              {['REPLIED', 'ACCEPTED', 'REJECTED', 'NO_REPLY', 'COOPERATING', 'COMPLETED', 'CANCELLED'].map(
                (status) => (
                  <button
                    key={status}
                    className="rounded border px-2 py-1 text-xs"
                    onClick={() => void transition(status)}
                  >
                    推进到 {outreachStatusLabel(status)}
                  </button>
                ),
              )}
            </div>
          ) : null}

          <FollowupPanel outreachId={detail.outreach.id} onChanged={() => void openDetail(detail.outreach.id)} />

          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">样品单</h3>
            {detail.sampleOrders.length === 0 ? (
              <p className="text-muted-foreground text-xs">这条邀约下还没有样品单。</p>
            ) : (
              <div className="overflow-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground text-left text-xs">
                      <th className="py-1">样品</th>
                      <th className="py-1">数量</th>
                      <th className="py-1">成本</th>
                      <th className="py-1">状态</th>
                      <th className="py-1">物流</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.sampleOrders.map((sample) => (
                      <tr key={sample.id} className="border-t">
                        <td className="py-1">
                          {sample.skuCode}
                          {sample.skuName ? ` · ${sample.skuName}` : ''}
                        </td>
                        <td className="py-1">{outreachCount(sample.quantity)}</td>
                        <td className="py-1">{outreachMoney(sample.sampleValue, sample.sampleCurrency)}</td>
                        <td className="py-1">
                          {sampleOrderStatusLabel(sample.status)}
                          {sample.statusDerived ? '（由包裹推导）' : ''}
                        </td>
                        <td className="text-muted-foreground py-1 text-xs">
                          {outboundText(sample)}
                          <br />
                          {sample.trackingAvailable ? '轨迹可查' : `轨迹不可查（归 ${sample.trackingOwnerModule} 管）`}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">佣金计提</h3>
            {detail.accruals.length === 0 ? (
              <p className="text-muted-foreground text-xs">这条邀约下还没有佣金计提。</p>
            ) : (
              <div className="overflow-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground text-left text-xs">
                      <th className="py-1">规则</th>
                      <th className="py-1">形态</th>
                      <th className="py-1">比例 / 固定额</th>
                      <th className="py-1">基数</th>
                      <th className="py-1">计提金额</th>
                      <th className="py-1">财务</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.accruals.map((accrual) => (
                      <tr key={accrual.id} className="border-t">
                        <td className="py-1">{accrual.ruleCode ?? '—'}</td>
                        <td className="py-1">{commissionTypeLabel(accrual.commissionType)}</td>
                        <td className="py-1">
                          {accrual.commissionType === 'RATE'
                            ? outreachRatio(accrual.commissionRate)
                            : outreachMoney(accrual.fixedAmount)}
                        </td>
                        <td className="py-1">{outreachMoney(accrual.baseAmount, accrual.baseCurrency)}</td>
                        <td className="py-1">
                          {outreachMoney(accrual.accruedAmount, accrual.accruedCurrency)}
                        </td>
                        <td className="text-muted-foreground py-1 text-xs">
                          {financeText(accrual)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function FollowupPanel({
  outreachId,
  onChanged,
}: {
  outreachId: string;
  onChanged: () => void;
}) {
  const [items, setItems] = useState<
    Awaited<ReturnType<typeof fetchInfluencerOutreachFollowups>> | null
  >(null);
  const [form, setForm] = useState({ followupType: 'CALL', content: '', nextFollowupAt: '' });
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setItems(await fetchInfluencerOutreachFollowups({ outreachId, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [outreachId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit() {
    if (!form.content.trim()) {
      setError('跟进内容必填');
      return;
    }
    setError('');
    try {
      await createInfluencerOutreachFollowup(outreachId, {
        followupType: form.followupType,
        content: form.content,
        nextFollowupAt: form.nextFollowupAt || undefined,
      });
      setForm({ followupType: 'CALL', content: '', nextFollowupAt: '' });
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">合作跟进</h3>
      <div className="flex flex-wrap items-end gap-2">
        <Field label="方式">
          <select
            className="rounded border px-2 py-1 text-sm"
            value={form.followupType}
            onChange={(e) => setForm({ ...form, followupType: e.target.value })}
          >
            {['CALL', 'IM', 'EMAIL', 'SAMPLE_FEEDBACK', 'NEGOTIATION', 'OTHER'].map((t) => (
              <option key={t} value={t}>
                {followupTypeLabel(t)}
              </option>
            ))}
          </select>
        </Field>
        <Field label="内容">
          <input
            className="rounded border px-2 py-1 text-sm"
            value={form.content}
            onChange={(e) => setForm({ ...form, content: e.target.value })}
          />
        </Field>
        <Field label="下次跟进时刻">
          <input
            className="rounded border px-2 py-1 text-sm"
            placeholder="2026-10-30T10:00"
            value={form.nextFollowupAt}
            onChange={(e) => setForm({ ...form, nextFollowupAt: e.target.value })}
          />
        </Field>
        <button className="rounded border px-3 py-1 text-sm" onClick={() => void submit()}>
          登记跟进
        </button>
      </div>
      {items && items.items.length === 0 ? (
        <p className="text-muted-foreground text-xs">还没有跟进记录。</p>
      ) : null}
      {items && items.items.length > 0 ? (
        <ul className="flex flex-col gap-1 text-sm">
          {items.items.map((f) => (
            <li key={f.id} className="border-t pt-1">
              <span className="text-muted-foreground text-xs">
                {followupTypeLabel(f.followupType)} · {new Date(f.occurredAt).toLocaleString('zh-CN')}
                {f.nextFollowupAt ? ` · 下次 ${new Date(f.nextFollowupAt).toLocaleString('zh-CN')}` : ''}
              </span>
              <p>{f.content}</p>
            </li>
          ))}
        </ul>
      ) : null}
      {error ? <p className="text-destructive text-xs">{error}</p> : null}
    </div>
  );
}

// ==================================================================
// 筛选条件
// ==================================================================

function SegmentsTab({ options }: { options: InfluencerOutreachOptions | null }) {
  const [page, setPage] = useState<InfluencerOutreachSegmentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [form, setForm] = useState<InfluencerOutreachSegmentInput | null>(null);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<InfluencerOutreachCandidatePage | null>(null);
  const [candidateSegmentId, setCandidateSegmentId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencerOutreachSegments({ size: 50 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit() {
    if (!form) return;
    const check = validateInfluencerOutreachSegment(form);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setError('');
    try {
      if (editingId) {
        await updateInfluencerOutreachSegment(editingId, form);
      } else {
        await saveInfluencerOutreachSegment(form);
      }
      setForm(null);
      setEditingId(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function preview(id: string) {
    setError('');
    setCandidateSegmentId(id);
    try {
      setCandidates(await fetchInfluencerOutreachCandidates(id, { size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section title="达人筛选条件（按规则筛选达人）">
        <div className="flex flex-wrap items-end gap-2">
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => setForm(emptyInfluencerOutreachSegmentInput())}
          >
            新建筛选条件
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 条件之间是 <strong>AND（交集）</strong>，标签编码之间是 <strong>OR（池子的并集）</strong>；
          黑名单里的达人默认排除（要给他们建联得显式打开开关）。
        </p>
        {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
        {!loading && page && page.items.length === 0 ? (
          <EmptyState hasFilter={false} onReset={() => void load()} />
        ) : null}
        {page && page.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">编码 / 名称</th>
                  <th className="py-1">条件</th>
                  <th className="py-1">黑名单</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">
                      {row.segmentCode} · {row.segmentName}
                    </td>
                    <td className="text-muted-foreground py-1 text-xs">
                      类目 {row.category || '不限'} · 层级 {row.followerTier || '不限'} · 粉丝 ≥{' '}
                      {row.minFollowerCount ?? '不限'} · 互动率 ≥{' '}
                      {row.minEngagementRate === null ? '不限' : outreachRatio(row.minEngagementRate)}{' '}
                      · GMV ≥ {row.minGmv === null ? '不限' : outreachMoney(row.minGmv)} · 标签{' '}
                      {row.tagCodes || '不限'}
                    </td>
                    <td className="py-1">{row.excludeBlacklisted ? '排除' : '包含'}</td>
                    <td className="py-1">{configStatusLabel(row.status)}</td>
                    <td className="flex gap-2 py-1">
                      <button className="rounded border px-2 py-1 text-xs" onClick={() => void preview(row.id)}>
                        看候选
                      </button>
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => {
                          setEditingId(row.id);
                          setForm({
                            segmentCode: row.segmentCode,
                            segmentName: row.segmentName,
                            description: row.description,
                            category: row.category,
                            followerTier: row.followerTier,
                            minFollowerCount:
                              row.minFollowerCount === null ? '' : String(row.minFollowerCount),
                            minEngagementRate:
                              row.minEngagementRate === null ? '' : String(row.minEngagementRate),
                            minGmv: row.minGmv === null ? '' : String(row.minGmv),
                            ratingTierCode: row.ratingTierCode,
                            tagCodes: row.tagCodes,
                            platformId: row.platformId ?? '',
                            shopCode: row.shopCode,
                            excludeBlacklisted: row.excludeBlacklisted,
                            status: row.status,
                            note: row.note,
                          });
                        }}
                      >
                        编辑
                      </button>
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => void deleteInfluencerOutreachSegment(row.id).then(load)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      {candidateSegmentId && candidates ? (
        <Section title="按规则筛出的候选达人（真筛选）">
          <p className="text-sm">共 {candidates.total} 人</p>
          {candidates.items.length === 0 ? (
            <p className="text-muted-foreground text-xs">
              没有达人命中这个筛选条件 —— 放宽条件或先在达人库里补效果数据。
            </p>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">达人</th>
                    <th className="py-1">类目</th>
                    <th className="py-1">粉丝</th>
                    <th className="py-1">互动率</th>
                    <th className="py-1">GMV</th>
                    <th className="py-1">标记</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.items.map((c) => (
                    <tr key={c.id} className="border-t">
                      <td className="py-1">
                        {c.displayName}
                        <span className="text-muted-foreground text-xs">（{c.id}）</span>
                      </td>
                      <td className="py-1">{c.category}</td>
                      <td className="py-1">{c.followerTier}</td>
                      <td className="py-1">{outreachRatio(c.engagementRate)}</td>
                      <td className="py-1">{outreachMoney(c.gmv, c.gmvCurrency)}</td>
                      <td className="py-1 text-xs">
                        {c.blacklisted ? '黑名单里 ' : ''}
                        {c.hasOpenOutreach ? '已有未走完的邀约' : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Section>
      ) : null}

      {form ? (
        <Section title={editingId ? '改筛选条件' : '新建筛选条件'}>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="编码">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.segmentCode}
                onChange={(e) => setForm({ ...form, segmentCode: e.target.value })}
              />
            </Field>
            <Field label="名称">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.segmentName}
                onChange={(e) => setForm({ ...form, segmentName: e.target.value })}
              />
            </Field>
            <Field label="类目（留空 = 不限）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.category}
                onChange={(e) => setForm({ ...form, category: e.target.value })}
              />
            </Field>
            <Field label="粉丝层级（留空 = 不限）">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.followerTier}
                onChange={(e) => setForm({ ...form, followerTier: e.target.value })}
              >
                <option value="">不限</option>
                {['NANO', 'MICRO', 'MID', 'MACRO', 'MEGA', 'TOP'].map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="粉丝数下限">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.minFollowerCount}
                onChange={(e) => setForm({ ...form, minFollowerCount: e.target.value })}
              />
            </Field>
            <Field label="互动率下限（0~1）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.minEngagementRate}
                onChange={(e) => setForm({ ...form, minEngagementRate: e.target.value })}
              />
            </Field>
            <Field label="GMV 下限">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.minGmv}
                onChange={(e) => setForm({ ...form, minGmv: e.target.value })}
              />
            </Field>
            <Field label="标签编码（逗号分隔）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.tagCodes}
                onChange={(e) => setForm({ ...form, tagCodes: e.target.value })}
              />
            </Field>
            <Field label="平台 id（留空 = 不限）">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.platformId}
                onChange={(e) => setForm({ ...form, platformId: e.target.value })}
              >
                <option value="">不限</option>
                {(options?.platforms ?? []).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}（{p.code}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="店铺编码（留空 = 不限）">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.shopCode}
                onChange={(e) => setForm({ ...form, shopCode: e.target.value })}
              >
                <option value="">不限</option>
                {(options?.shopCodes ?? []).map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </Field>
            <label className="flex items-center gap-1 text-sm">
              <input
                type="checkbox"
                checked={form.excludeBlacklisted}
                onChange={(e) => setForm({ ...form, excludeBlacklisted: e.target.checked })}
              />
              排除黑名单里的达人
            </label>
            <Field label="状态">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.status}
                onChange={(e) => setForm({ ...form, status: e.target.value })}
              >
                <option value="ACTIVE">启用</option>
                <option value="PAUSED">停用</option>
              </select>
            </Field>
            <button className="rounded border px-3 py-1 text-sm" onClick={() => void submit()}>
              保存
            </button>
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setForm(null);
                setEditingId(null);
                setFormErrors([]);
              }}
            >
              取消
            </button>
          </div>
          <FormErrors errors={formErrors} />
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

// ==================================================================
// 邀约模板
// ==================================================================

function TemplatesTab({ options }: { options: InfluencerOutreachOptions | null }) {
  const [page, setPage] = useState<InfluencerOutreachTemplatePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [form, setForm] = useState<InfluencerOutreachTemplateInput | null>(null);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [preview, setPreview] = useState<InfluencerOutreachTemplatePreview | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencerOutreachTemplates({ size: 50 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit() {
    if (!form) return;
    const check = validateInfluencerOutreachTemplate(form, options?.templateVariables ?? []);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setError('');
    try {
      if (editingId) {
        await updateInfluencerOutreachTemplate(editingId, form);
      } else {
        await saveInfluencerOutreachTemplate(form);
      }
      setForm(null);
      setEditingId(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function doPreview(id: string) {
    setError('');
    try {
      const influencerId = window.prompt('预览要用哪个达人 id？（在达人库里复制）');
      if (!influencerId) return;
      setPreview(await fetchInfluencerOutreachTemplatePreview(id, influencerId.trim()));
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section title="邀约模板（模板化私信邀约）">
        <div className="flex flex-wrap items-end gap-2">
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => setForm(emptyInfluencerOutreachTemplateInput())}
          >
            新建模板
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 模板变量是<strong>闭合词表</strong>（没有「其它」桶）：未登记的占位符在保存时就被拒 ——
          留着它等于把一串 <code>{'{{xxx}}'}</code> 原样发给达人。
        </p>
        {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
        {!loading && page && page.items.length === 0 ? (
          <EmptyState hasFilter={false} onReset={() => void load()} />
        ) : null}
        {page && page.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">编码 / 名称</th>
                  <th className="py-1">平台</th>
                  <th className="py-1">正文</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">
                      {row.templateCode} · {row.templateName}
                    </td>
                    <td className="py-1">{row.platformCode}</td>
                    <td className="text-muted-foreground max-w-md truncate py-1 text-xs">{row.body}</td>
                    <td className="py-1">{configStatusLabel(row.status)}</td>
                    <td className="flex gap-2 py-1">
                      <button className="rounded border px-2 py-1 text-xs" onClick={() => void doPreview(row.id)}>
                        预览
                      </button>
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => {
                          setEditingId(row.id);
                          setForm({
                            templateCode: row.templateCode,
                            templateName: row.templateName,
                            platformId: row.platformId,
                            subject: row.subject,
                            body: row.body,
                            status: row.status,
                            note: row.note,
                          });
                        }}
                      >
                        编辑
                      </button>
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => void deleteInfluencerOutreachTemplate(row.id).then(load)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      {preview ? (
        <Section title="模板预览（真渲染）">
          <p className="text-sm">{preview.subject}</p>
          <pre className="text-sm whitespace-pre-wrap">{preview.messageBody}</pre>
          <p className="text-muted-foreground text-xs">
            渲染取值：{Object.entries(preview.values).map(([k, v]) => `${k}=${v}`).join(' · ')}
          </p>
        </Section>
      ) : null}

      {form ? (
        <Section title={editingId ? '改模板' : '新建模板'}>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="编码">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.templateCode}
                onChange={(e) => setForm({ ...form, templateCode: e.target.value })}
              />
            </Field>
            <Field label="名称">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.templateName}
                onChange={(e) => setForm({ ...form, templateName: e.target.value })}
              />
            </Field>
            <Field label="平台">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.platformId}
                onChange={(e) => setForm({ ...form, platformId: e.target.value })}
              >
                <option value="">请选择平台</option>
                {(options?.platforms ?? []).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}（{p.code}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="标题">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.subject}
                onChange={(e) => setForm({ ...form, subject: e.target.value })}
              />
            </Field>
            <Field label="状态">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={form.status}
                onChange={(e) => setForm({ ...form, status: e.target.value })}
              >
                <option value="ACTIVE">启用</option>
                <option value="PAUSED">停用</option>
              </select>
            </Field>
          </div>
          <label className="flex flex-col gap-1 text-sm">
            <span>正文（可含 {'{{变量}}'}）</span>
            <textarea
              className="rounded border px-2 py-1 text-sm"
              rows={5}
              value={form.body}
              onChange={(e) => setForm({ ...form, body: e.target.value })}
            />
          </label>
          <p className="text-muted-foreground text-xs">
            可用变量：
            {(options?.templateVariables ?? [])
              .map((v) => `{{${v.name}}}` + (v.required ? '（必填）' : ''))
              .join(' · ')}
          </p>
          <FormErrors errors={formErrors} />
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submit()}>
            保存
          </button>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

// ==================================================================
// 样品与物流
// ==================================================================

function SamplesTab({
  options,
  onChanged,
}: {
  options: InfluencerOutreachOptions | null;
  onChanged: () => Promise<void>;
}) {
  const [page, setPage] = useState<InfluencerSampleOrderPage | null>(null);
  const [shipments, setShipments] = useState<InfluencerSampleShipmentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [form, setForm] = useState<InfluencerSampleOrderInput | null>(null);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [shipmentForm, setShipmentForm] = useState({
    sampleOrderId: '',
    carrierCode: '',
    trackingNo: '',
    packageCount: '1',
  });
  const [policies, setPolicies] = useState<InfluencerOutreachPolicyPage | null>(null);
  const [policyForm, setPolicyForm] = useState<InfluencerOutreachPolicyInput>(
    emptyInfluencerOutreachPolicyInput(),
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [orders, packs] = await Promise.all([
        fetchInfluencerSampleOrders({ size: 50 }),
        fetchInfluencerSampleShipments({ size: 50 }),
      ]);
      setPage(orders);
      setShipments(packs);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    void (async () => {
      try {
        setPolicies(await fetchInfluencerOutreachPolicies({ size: 50 }));
      } catch (e) {
        setError(errorText(e));
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load]);

  async function submit() {
    if (!form) return;
    const check = validateInfluencerSampleOrder(form);
    setFormErrors(check.errors);
    if (!check.ok) return;
    setError('');
    try {
      if (editingId) {
        await updateInfluencerSampleOrder(editingId, form);
      } else {
        await createInfluencerSampleOrder(form);
      }
      setForm(null);
      setEditingId(null);
      await load();
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitShipment() {
    if (!shipmentForm.sampleOrderId) {
      setError('登记包裹要选样品单');
      return;
    }
    setError('');
    try {
      await createInfluencerSampleShipment(shipmentForm.sampleOrderId, {
        carrierCode: shipmentForm.carrierCode,
        trackingNo: shipmentForm.trackingNo,
        packageCount: shipmentForm.packageCount,
      });
      setShipmentForm({ sampleOrderId: '', carrierCode: '', trackingNo: '', packageCount: '1' });
      await load();
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section title="样品单（寄样管理）">
        <div className="flex flex-wrap items-end gap-2">
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => setForm(emptyInfluencerSampleOrderInput())}
          >
            新建样品单
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 样品单的「已送达 / 已签收」<strong>由包裹推导</strong>（不是人填的）；手工推进只能到
          「已寄出 / 已取消」。8 仓库与 10 物流未交付 ⇒ 出库只是本地事实、真实轨迹不可查。
        </p>
        {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
        {!loading && page && page.items.length === 0 ? (
          <EmptyState hasFilter={false} onReset={() => void load()} />
        ) : null}
        {page && page.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">达人</th>
                  <th className="py-1">样品</th>
                  <th className="py-1">数量</th>
                  <th className="py-1">成本</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">出库 / 物流</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">{row.influencerName}</td>
                    <td className="py-1">
                      {row.skuCode}
                      {row.skuName ? ` · ${row.skuName}` : ''}
                    </td>
                    <td className="py-1">{outreachCount(row.quantity)}</td>
                    <td className="py-1">{outreachMoney(row.sampleValue, row.sampleCurrency)}</td>
                    <td className="py-1">
                      {sampleOrderStatusLabel(row.status)}
                      {row.statusDerived ? '（由包裹推导）' : ''}
                    </td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {outboundText(row)}
                      <br />
                      {row.trackingAvailable
                        ? '轨迹可查'
                        : `轨迹不可查（归 ${row.trackingOwnerModule} 管）`}
                    </td>
                    <td className="flex gap-2 py-1">
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => {
                          setEditingId(row.id);
                          setForm({
                            outreachId: row.outreachId,
                            skuCode: row.skuCode,
                            skuName: row.skuName,
                            quantity: row.quantity === null ? '' : String(row.quantity),
                            sampleValue: row.sampleValue === null ? '' : String(row.sampleValue),
                            sampleCurrency: row.sampleCurrency,
                            recipientName: row.recipientName,
                            recipientPhone: row.recipientPhone,
                            recipientAddress: row.recipientAddress,
                            note: row.note,
                          });
                        }}
                      >
                        编辑
                      </button>
                      {row.status === 'REQUESTED' ? (
                        <>
                          <button
                            className="rounded border px-2 py-1 text-xs"
                            onClick={() =>
                              void transitionInfluencerSampleOrder(row.id, { status: 'OUTBOUNDED' })
                                .then(load)
                                .then(onChanged)
                            }
                          >
                            记本地已出库
                          </button>
                          <button
                            className="rounded border px-2 py-1 text-xs"
                            onClick={() =>
                              void transitionInfluencerSampleOrder(row.id, { status: 'CANCELLED' })
                                .then(load)
                                .then(onChanged)
                            }
                          >
                            取消
                          </button>
                        </>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      <Section title="样品包裹（发货 / 物流单号 / 签收）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="样品单 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={shipmentForm.sampleOrderId}
              onChange={(e) => setShipmentForm({ ...shipmentForm, sampleOrderId: e.target.value })}
            />
          </Field>
          <Field label="承运商">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={shipmentForm.carrierCode}
              onChange={(e) => setShipmentForm({ ...shipmentForm, carrierCode: e.target.value })}
            />
          </Field>
          <Field label="物流单号">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={shipmentForm.trackingNo}
              onChange={(e) => setShipmentForm({ ...shipmentForm, trackingNo: e.target.value })}
            />
          </Field>
          <Field label="件数">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={shipmentForm.packageCount}
              onChange={(e) => setShipmentForm({ ...shipmentForm, packageCount: e.target.value })}
            />
          </Field>
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitShipment()}>
            登记包裹（发货）
          </button>
        </div>
        {shipments && shipments.items.length === 0 ? (
          <p className="text-muted-foreground text-xs">还没有包裹记录。</p>
        ) : null}
        {shipments && shipments.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">样品单 id</th>
                  <th className="py-1">承运商 / 单号</th>
                  <th className="py-1">件数</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">发货 / 签收</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {shipments.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="text-muted-foreground py-1 text-xs">{row.sampleOrderId}</td>
                    <td className="py-1">
                      {row.carrierCode || '—'} · {row.trackingNo || '—'}
                    </td>
                    <td className="py-1">{outreachCount(row.packageCount)}</td>
                    <td className="py-1">{shipmentStatusLabel(row.status)}</td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {new Date(row.shippedAt).toLocaleString('zh-CN')}
                      {row.signedAt ? ` · 签收 ${new Date(row.signedAt).toLocaleString('zh-CN')}` : ''}
                      <br />
                      {row.trackingAvailable
                        ? '轨迹可查'
                        : `轨迹不可查（归 ${row.trackingOwnerModule} 管）`}
                    </td>
                    <td className="flex flex-wrap gap-1 py-1">
                      {['IN_TRANSIT', 'DELIVERED', 'SIGNED', 'LOST', 'RETURNED'].map((status) => (
                        <button
                          key={status}
                          className="rounded border px-2 py-1 text-xs"
                          onClick={() =>
                            void transitionInfluencerSampleShipment(row.id, {
                              status,
                              signedBy: status === 'SIGNED' ? '（签收人）' : '',
                            })
                              .then(load)
                              .then(onChanged)
                          }
                        >
                          {shipmentStatusLabel(status)}
                        </button>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      <Section title="风控策略（每日发送上限 + 最小间隔）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="平台">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={policyForm.platformId}
              onChange={(e) => setPolicyForm({ ...policyForm, platformId: e.target.value })}
            >
              <option value="">请选择平台</option>
              {(options?.platforms ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}（{p.code}）
                </option>
              ))}
            </select>
          </Field>
          <Field label="每日发送上限">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={policyForm.dailySendLimit}
              onChange={(e) => setPolicyForm({ ...policyForm, dailySendLimit: e.target.value })}
            />
          </Field>
          <Field label="最小间隔（秒）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={policyForm.minIntervalSeconds}
              onChange={(e) => setPolicyForm({ ...policyForm, minIntervalSeconds: e.target.value })}
            />
          </Field>
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() =>
              void saveInfluencerOutreachPolicy(policyForm)
                .then(() => fetchInfluencerOutreachPolicies({ size: 50 }))
                .then(setPolicies)
                .catch((e) => setError(errorText(e)))
            }
          >
            保存策略
          </button>
        </div>
        {policies && policies.items.length === 0 ? (
          <p className="text-muted-foreground text-xs">
            还没有配置策略 —— 当前用默认值（每日 20 条 / 最小间隔 60 秒）。
          </p>
        ) : null}
        {policies && policies.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">平台</th>
                  <th className="py-1">每日上限</th>
                  <th className="py-1">最小间隔（秒）</th>
                  <th className="py-1">状态</th>
                </tr>
              </thead>
              <tbody>
                {policies.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">{row.platformCode}</td>
                    <td className="py-1">{row.dailySendLimit}</td>
                    <td className="py-1">{row.minIntervalSeconds}</td>
                    <td className="py-1">{configStatusLabel(row.status)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      {form ? (
        <Section title={editingId ? '改样品单' : '新建样品单'}>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="建联记录 id">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.outreachId}
                onChange={(e) => setForm({ ...form, outreachId: e.target.value })}
              />
            </Field>
            <Field label="样品 SKU 编码">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.skuCode}
                onChange={(e) => setForm({ ...form, skuCode: e.target.value })}
              />
            </Field>
            <Field label="样品名称">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.skuName}
                onChange={(e) => setForm({ ...form, skuName: e.target.value })}
              />
            </Field>
            <Field label="数量">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.quantity}
                onChange={(e) => setForm({ ...form, quantity: e.target.value })}
              />
            </Field>
            <Field label="样品成本（留空 = 未录入）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.sampleValue}
                onChange={(e) => setForm({ ...form, sampleValue: e.target.value })}
              />
            </Field>
            <Field label="币种">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.sampleCurrency}
                onChange={(e) => setForm({ ...form, sampleCurrency: e.target.value })}
              />
            </Field>
            <Field label="收件人">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.recipientName}
                onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
              />
            </Field>
            <Field label="收件电话">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={form.recipientPhone}
                onChange={(e) => setForm({ ...form, recipientPhone: e.target.value })}
              />
            </Field>
          </div>
          <label className="flex flex-col gap-1 text-sm">
            <span>收件地址</span>
            <textarea
              className="rounded border px-2 py-1 text-sm"
              rows={2}
              value={form.recipientAddress}
              onChange={(e) => setForm({ ...form, recipientAddress: e.target.value })}
            />
          </label>
          <FormErrors errors={formErrors} />
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submit()}>
            保存
          </button>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

// ==================================================================
// 佣金
// ==================================================================

function CommissionTab({
  options,
  onChanged,
}: {
  options: InfluencerOutreachOptions | null;
  onChanged: () => Promise<void>;
}) {
  const [rules, setRules] = useState<InfluencerCommissionRulePage | null>(null);
  const [accruals, setAccruals] = useState<InfluencerCommissionAccrualPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ruleForm, setRuleForm] = useState<InfluencerCommissionRuleInput | null>(null);
  const [ruleErrors, setRuleErrors] = useState<string[]>([]);
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null);
  const [accrualForm, setAccrualForm] = useState<InfluencerCommissionAccrualInput>(
    emptyInfluencerCommissionAccrualInput(),
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [rulePage, accrualPage] = await Promise.all([
        fetchInfluencerCommissionRules({ size: 50 }),
        fetchInfluencerCommissionAccruals({ size: 50 }),
      ]);
      setRules(rulePage);
      setAccruals(accrualPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitRule() {
    if (!ruleForm) return;
    const check = validateInfluencerCommissionRule(ruleForm);
    setRuleErrors(check.errors);
    if (!check.ok) return;
    setError('');
    try {
      if (editingRuleId) {
        await updateInfluencerCommissionRule(editingRuleId, ruleForm);
      } else {
        await saveInfluencerCommissionRule(ruleForm);
      }
      setRuleForm(null);
      setEditingRuleId(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitAccrual() {
    if (!accrualForm.outreachId) {
      setError('登记计提要选建联记录 id');
      return;
    }
    setError('');
    try {
      await createInfluencerCommissionAccrual(accrualForm);
      setAccrualForm(emptyInfluencerCommissionAccrualInput());
      await load();
      await onChanged();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Section title="佣金规则（比例 / 固定额）">
        <div className="flex flex-wrap items-end gap-2">
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => setRuleForm(emptyInfluencerCommissionRuleInput())}
          >
            新建规则
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 佣金只有两种形态（按比例 / 固定额），<strong>混合形态被拒</strong> ——
          「先给 5% 再减 20 美元」在计提时算不清。
        </p>
        {loading ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
        {!loading && rules && rules.items.length === 0 ? (
          <EmptyState hasFilter={false} onReset={() => void load()} />
        ) : null}
        {rules && rules.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">编码 / 名称</th>
                  <th className="py-1">适用范围</th>
                  <th className="py-1">形态</th>
                  <th className="py-1">比例 / 固定额</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {rules.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">
                      {row.ruleCode} · {row.ruleName}
                    </td>
                    <td className="py-1">
                      {commissionScopeLabel(row.scopeType)}
                      {row.segmentName ? `（${row.segmentName}）` : ''}
                      {row.influencerName ? `（${row.influencerName}）` : ''}
                    </td>
                    <td className="py-1">{commissionTypeLabel(row.commissionType)}</td>
                    <td className="py-1">
                      {row.commissionType === 'RATE'
                        ? outreachRatio(row.commissionRate)
                        : outreachMoney(row.fixedAmount, row.currency)}
                    </td>
                    <td className="py-1">{configStatusLabel(row.status)}</td>
                    <td className="flex gap-2 py-1">
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => {
                          setEditingRuleId(row.id);
                          setRuleForm({
                            ruleCode: row.ruleCode,
                            ruleName: row.ruleName,
                            scopeType: row.scopeType,
                            segmentId: row.segmentId ?? '',
                            influencerId: row.influencerId ?? '',
                            commissionType: row.commissionType,
                            commissionRate:
                              row.commissionRate === null ? '' : String(row.commissionRate),
                            fixedAmount: row.fixedAmount === null ? '' : String(row.fixedAmount),
                            currency: row.currency,
                            effectiveFrom: '',
                            effectiveTo: '',
                            status: row.status,
                            note: row.note,
                          });
                        }}
                      >
                        编辑
                      </button>
                      <button
                        className="rounded border px-2 py-1 text-xs"
                        onClick={() => void deleteInfluencerCommissionRule(row.id).then(load)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      <Section title="佣金计提（🔴 只落本地事实，未进费用归集）">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="建联记录 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={accrualForm.outreachId}
              onChange={(e) => setAccrualForm({ ...accrualForm, outreachId: e.target.value })}
            />
          </Field>
          <Field label="规则 id（留空 = 自动解析生效规则）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={accrualForm.ruleId}
              onChange={(e) => setAccrualForm({ ...accrualForm, ruleId: e.target.value })}
            />
          </Field>
          <Field label="计提基数（人工登记的归因 GMV）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={accrualForm.baseAmount}
              onChange={(e) => setAccrualForm({ ...accrualForm, baseAmount: e.target.value })}
            />
          </Field>
          <Field label="基数币种">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={accrualForm.baseCurrency}
              onChange={(e) => setAccrualForm({ ...accrualForm, baseCurrency: e.target.value })}
            />
          </Field>
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitAccrual()}>
            登记计提
          </button>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 计提金额由服务端按规则算；12 财务未交付 ⇒ 未进费用归集，**达人佣金成本暂时不进利润表**
          （归因口径归 14.5.2.1，也未交付）。
        </p>
        {accruals && accruals.items.length === 0 ? (
          <p className="text-muted-foreground text-xs">还没有佣金计提记录。</p>
        ) : null}
        {accruals && accruals.items.length > 0 ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">达人</th>
                  <th className="py-1">规则</th>
                  <th className="py-1">形态</th>
                  <th className="py-1">比例 / 固定额</th>
                  <th className="py-1">基数</th>
                  <th className="py-1">计提金额</th>
                  <th className="py-1">财务</th>
                </tr>
              </thead>
              <tbody>
                {accruals.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-1">{row.influencerName}</td>
                    <td className="py-1">{row.ruleCode ?? '—'}</td>
                    <td className="py-1">{commissionTypeLabel(row.commissionType)}</td>
                    <td className="py-1">
                      {row.commissionType === 'RATE'
                        ? outreachRatio(row.commissionRate)
                        : outreachMoney(row.fixedAmount)}
                    </td>
                    <td className="py-1">{outreachMoney(row.baseAmount, row.baseCurrency)}</td>
                    <td className="py-1">{outreachMoney(row.accruedAmount, row.accruedCurrency)}</td>
                    <td className="text-muted-foreground py-1 text-xs">{financeText(row)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Section>

      {ruleForm ? (
        <Section title={editingRuleId ? '改佣金规则' : '新建佣金规则'}>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="编码">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.ruleCode}
                onChange={(e) => setRuleForm({ ...ruleForm, ruleCode: e.target.value })}
              />
            </Field>
            <Field label="名称">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.ruleName}
                onChange={(e) => setRuleForm({ ...ruleForm, ruleName: e.target.value })}
              />
            </Field>
            <Field label="适用范围">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.scopeType}
                onChange={(e) => setRuleForm({ ...ruleForm, scopeType: e.target.value })}
              >
                {(options?.commissionScopes ?? []).map((o) => (
                  <option key={o.code} value={o.code}>
                    {commissionScopeLabel(o.code)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="筛选条件 id（按分群时）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.segmentId}
                onChange={(e) => setRuleForm({ ...ruleForm, segmentId: e.target.value })}
              />
            </Field>
            <Field label="达人 id（按达人时）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.influencerId}
                onChange={(e) => setRuleForm({ ...ruleForm, influencerId: e.target.value })}
              />
            </Field>
            <Field label="形态">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.commissionType}
                onChange={(e) => setRuleForm({ ...ruleForm, commissionType: e.target.value })}
              >
                {(options?.commissionTypes ?? []).map((o) => (
                  <option key={o.code} value={o.code}>
                    {commissionTypeLabel(o.code)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="佣金比例（0~1）">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.commissionRate}
                onChange={(e) => setRuleForm({ ...ruleForm, commissionRate: e.target.value })}
              />
            </Field>
            <Field label="固定金额">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.fixedAmount}
                onChange={(e) => setRuleForm({ ...ruleForm, fixedAmount: e.target.value })}
              />
            </Field>
            <Field label="币种">
              <input
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.currency}
                onChange={(e) => setRuleForm({ ...ruleForm, currency: e.target.value })}
              />
            </Field>
            <Field label="状态">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={ruleForm.status}
                onChange={(e) => setRuleForm({ ...ruleForm, status: e.target.value })}
              >
                <option value="ACTIVE">启用</option>
                <option value="PAUSED">停用</option>
              </select>
            </Field>
          </div>
          <FormErrors errors={ruleErrors} />
          <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitRule()}>
            保存
          </button>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

// ==================================================================
// 通用小组件
// ==================================================================

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}

function FormErrors({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <ul className="text-destructive flex flex-col gap-1 text-sm">
      {errors.map((e) => (
        <li key={e}>{e}</li>
      ))}
    </ul>
  );
}

/**
 * 🔴 空态要**区分两种**：
 *
 * - 「筛选无结果」⇒ 条件太窄，提示放宽条件（带「清空筛选」按钮）；
 * - 「数据还是空的」⇒ 还没录过，提示去建一条（带「刷新」按钮）。
 *
 * 混成一句「暂无数据」的话，运营分不清是自己筛错了还是根本没建过。
 */
function EmptyState({ hasFilter, onReset }: { hasFilter: boolean; onReset: () => void }) {
  return (
    <div className="text-muted-foreground flex flex-col gap-2 text-sm">
      <p>
        {hasFilter
          ? '当前筛选条件下没有记录 —— 可能是条件太窄，放宽条件再看看。'
          : '还没有任何记录 —— 先建一条再回来。'}
      </p>
      <button className="w-fit rounded border px-3 py-1 text-sm" onClick={onReset}>
        {hasFilter ? '清空筛选' : '刷新'}
      </button>
    </div>
  );
}

function Pagination({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const last = Math.max(Math.ceil(total / Math.max(size, 1)) - 1, 0);
  return (
    <div className="flex items-center gap-2 text-sm">
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        上一页
      </button>
      <span className="text-muted-foreground text-xs">
        第 {page + 1} / {last + 1} 页 · 共 {total} 条
      </span>
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page >= last}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </button>
    </div>
  );
}
