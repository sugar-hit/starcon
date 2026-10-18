'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlarmClock, RefreshCw, ShieldCheck, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AftersalesIntakeCapabilities,
  AftersalesIntakePage,
  AftersalesIntakeResult,
  AftersalesIntakeVerdictView,
  AftersalesOwnerRosterView,
  AftersalesReplySlaView,
} from '../../../../../packages/api-contracts/src/index';
import {
  createAftersalesReplySla,
  eligibilityBadgeClass,
  eligibilityPresentation,
  fetchAftersalesIntakeCapabilities,
  fetchAftersalesIntakeList,
  fetchAftersalesOwnerRoster,
  fetchAftersalesReplySla,
  humanizeHours,
  mustRefundBadgeClass,
  mustRefundBasisText,
  mustRefundPresentation,
  orderAnchorText,
  ownerModeText,
  rejudgeAftersalesIntake,
  replyDueBasisText,
  retireAftersalesReplySla,
  submitAftersalesIntake,
  updateAftersalesOwnerRoster,
  upsertAftersalesOwner,
  windowMarginText,
} from './aftersales-intake-model';

/**
 * 11.1.1.3 售后受理。
 *
 * <p>蓝图：「受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、
 * 设置回复时限。」输出句：「售后单（待处理）；政策判定结果；责任人分配。」
 * 加粗句：「<b>必退情形（平台强制、收到即退）要自动判定</b>，人工不能为了业绩拖着不退导致平台判责。」
 *
 * <p><b>本页面对那句加粗句负责的三件事</b>：
 *
 * <ol>
 *   <li><b>「必退且回复已超时」是页面顶部的第一张卡</b>，用警示色渲染。
 *       拖着不退的第一步就是看不见，所以这个数必须比「一共多少张」更靠前。</li>
 *   <li><b>每一行都带四档视觉的必退徽标</b>（必退超时 / 必退 / 非必退 / 判定未知），
 *       并显式写出**依据**（平台声明 or 原因分类命中哪个情形）——
 *       一个没有依据的「必退」标签运营不敢信也不敢不改。</li>
 *   <li><b>判定依据全部来自后端的冻结凭据</b>，前端不重算售后期、不重算必退。
 *       两处各算一遍必然漂移，而漂移的方向通常是「显示正常」。</li>
 * </ol>
 *
 * <p><b>受理表单刻意不含「我要不要必退」这个开关</b>：必退是平台强制的事实，
 * 人工自封必退等于给自己开一张「不许拒绝」的免死金牌（后端也会拒）。
 * 表单里只有处理方案与责任人可控 —— 那是人的权限所在。
 *
 * <p><b>回复时限策略在页面上是可见配置</b>：因为 17.5.1.1 里**没有**售后回复时限参数，
 * 「没配 SLA 就受理失败」这件事必须让运营看得见、并且能当场配一档。
 */

/** 受理表单的默认初值。金额/数量刻意留空：这两个必须由人填，系统不猜。 */
const EMPTY_INTAKE = {
  orderId: '',
  caseType: 'RETURN_REFUND',
  reasonCode: 'QUALITY_DEFECT',
  requestedAmount: '',
  currency: 'USD',
  requestedQuantity: '1',
  intakeSource: 'INTERNAL_ENTRY',
  intakeChannel: 'INTERNAL_NOTE',
  intakeReference: '',
  platformMustRefund: false,
  buyerRequest: '',
  handlingPlan: 'RETURN_REFUND',
  ownerRef: '',
};

const REASON_OPTIONS = [
  ['QUALITY_DEFECT', '质量问题'],
  ['DESCRIPTION_MISMATCH', '描述不符'],
  ['SIZE_MISMATCH', '尺码不符'],
  ['LOGISTICS_DAMAGE', '物流损坏'],
  ['BUYER_REASON', '买家原因'],
  ['MISTAKE_BUY', '误购'],
  ['FRAUD_RETURN', '欺诈退货'],
] as const;

const CASE_TYPES = [
  ['RETURN_REFUND', '退货退款'],
  ['REFUND_ONLY', '仅退款'],
  ['EXCHANGE', '换货'],
  ['REISSUE', '补发'],
  ['REPAIR', '维修'],
] as const;

const PLAN_OPTIONS = [
  ['RETURN_REFUND', '退货退款'],
  ['REFUND_ONLY', '仅退款'],
  ['EXCHANGE', '换货'],
  ['REISSUE', '补发'],
  ['REPAIR', '维修'],
  ['REJECT', '不受理'],
] as const;

function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('zh-CN', { hour12: false });
}

export function AftersalesIntakeWorkspace() {
  const [params, setParams] = useState({
    eligibility: '',
    mustRefundOnly: false,
    keyword: '',
    currentOnly: true,
    sort: 'MUST_REFUND_FIRST',
    page: 0,
    size: 50,
  });
  const [page, setPage] = useState<AftersalesIntakePage | null>(null);
  const [caps, setCaps] = useState<AftersalesIntakeCapabilities | null>(null);
  const [roster, setRoster] = useState<AftersalesOwnerRosterView[]>([]);
  const [sla, setSla] = useState<AftersalesReplySlaView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState(EMPTY_INTAKE);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<AftersalesIntakeResult | null>(null);

  const [slaHours, setSlaHours] = useState('24');
  const [slaScope, setSlaScope] = useState('');
  const [slaBusy, setSlaBusy] = useState(false);
  const [slaError, setSlaError] = useState<string | null>(null);

  const [ownerRef, setOwnerRef] = useState('');
  const [ownerWeight, setOwnerWeight] = useState('1');
  const [ownerBusy, setOwnerBusy] = useState(false);
  const [ownerError, setOwnerError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, capabilities, owners, effective] = await Promise.all([
        fetchAftersalesIntakeList({
          eligibility: params.eligibility || undefined,
          mustRefund: params.mustRefundOnly ? true : undefined,
          keyword: params.keyword || undefined,
          currentOnly: params.currentOnly,
          sort: params.sort,
          page: params.page,
          size: params.size,
        }),
        fetchAftersalesIntakeCapabilities(),
        fetchAftersalesOwnerRoster(),
        fetchAftersalesReplySla().catch(() => null),
      ]);
      setPage(list);
      setCaps(capabilities);
      setRoster(owners);
      setSla(effective);
    } catch (e) {
      setError(e instanceof Error ? e.message : '售后受理加载失败');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 受理是否可以直接提交：订单号与金额必填，其余由后端继续校验。 */
  const intakeReady = useMemo(
    () => form.orderId.trim().length > 0 && form.requestedAmount.trim().length > 0,
    [form],
  );

  async function submitIntake() {
    setSubmitting(true);
    setSubmitError(null);
    setSubmitted(null);
    try {
      const result = await submitAftersalesIntake({
        orderId: form.orderId.trim(),
        caseType: form.caseType,
        reasonCode: form.reasonCode,
        requestedAmount: form.requestedAmount.trim(),
        currency: form.currency.trim().toUpperCase(),
        requestedQuantity: form.requestedQuantity.trim() || '1',
        intakeSource: form.intakeSource,
        intakeChannel: form.intakeChannel,
        intakeReference: form.intakeReference.trim() || null,
        platformMustRefund:
          form.intakeSource === 'PLATFORM_MESSAGE' && form.platformMustRefund,
        buyerRequest: form.buyerRequest.trim() || null,
        handlingPlan: form.handlingPlan,
        ownerRef: form.ownerRef.trim() || null,
      });
      setSubmitted(result);
      setForm(EMPTY_INTAKE);
      await load();
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : '受理失败');
    } finally {
      setSubmitting(false);
    }
  }

  async function rejudge(caseId: string) {
    setSubmitError(null);
    try {
      await rejudgeAftersalesIntake(caseId);
      await load();
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : '重新判定失败');
    }
  }

  async function saveSla() {
    setSlaBusy(true);
    setSlaError(null);
    try {
      const hours = Number(slaHours);
      if (!Number.isFinite(hours) || hours <= 0) {
        throw new Error('回复时限必须是大于 0 的整数小时');
      }
      await createAftersalesReplySla({
        platformScope: slaScope,
        replySlaHours: Math.trunc(hours),
        effectiveFrom: new Date().toISOString(),
      });
      await load();
    } catch (e) {
      setSlaError(e instanceof Error ? e.message : '回复时限策略保存失败');
    } finally {
      setSlaBusy(false);
    }
  }

  async function retireSla(id: string) {
    setSlaError(null);
    try {
      await retireAftersalesReplySla(id);
      await load();
    } catch (e) {
      setSlaError(e instanceof Error ? e.message : '收口失败');
    }
  }

  async function addOwner() {
    setOwnerBusy(true);
    setOwnerError(null);
    try {
      await upsertAftersalesOwner({
        ownerRef: ownerRef.trim(),
        weight: Number(ownerWeight) || 1,
      });
      setOwnerRef('');
      await load();
    } catch (e) {
      setOwnerError(e instanceof Error ? e.message : '责任人登记失败');
    } finally {
      setOwnerBusy(false);
    }
  }

  async function toggleOwner(row: AftersalesOwnerRosterView) {
    setOwnerError(null);
    try {
      await updateAftersalesOwnerRoster(row.id, { acceptingNewCases: !row.acceptingNewCases });
      await load();
    } catch (e) {
      setOwnerError(e instanceof Error ? e.message : '责任人调整失败');
    }
  }

  const overdueSet = useMemo(() => {
    const at = page?.evaluatedAt ? new Date(page.evaluatedAt).getTime() : Date.now();
    const set = new Set<string>();
    for (const row of page?.items ?? []) {
      const due = new Date(row.replyDueAt).getTime();
      if (Number.isFinite(due) && due < at) set.add(row.caseId);
    }
    return set;
  }, [page]);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="售后受理"
        description="判定是否在售后期内、是否符合条件、指定责任人与处理方案、设置回复时限。必退情形由系统自动判定，平台强制不可在本地降级。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 必退超时卡排第一：这是打开这个页面唯一要先看的数。 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="必退且回复已超时"
          value={page?.summary.replyOverdueMustRefundCount ?? 0}
          icon={<AlarmClock className="h-4 w-4" />}
        />
        <StatCard
          label="必退"
          value={page?.summary.mustRefundCount ?? 0}
          icon={<ShieldCheck className="h-4 w-4" />}
        />
        <StatCard label="超窗待裁定" value={page?.summary.outOfWindowPendingCount ?? 0} />
        <StatCard label="待认领" value={page?.summary.unassignedCount ?? 0} />
      </div>

      {caps && (
        <SectionCard title="受理能力与政策来源" description={caps.policySource}>
          <ul className="text-muted-foreground flex list-disc flex-col gap-1 pl-5 text-copy-13">
            <li>
              售后期限参数 <code className="text-foreground">{caps.returnWindowParameter}</code>
              ：当前生效 <strong className="text-foreground">{caps.effectiveReturnWindowDays ?? '—'}</strong> 天
              （档位 {caps.policyScopeType ?? '—'}）
            </li>
            <li>
              必退情形参数 <code className="text-foreground">{caps.mustRefundParameter}</code>
              ：当前生效{' '}
              <strong className="text-foreground">
                {caps.effectiveMustRefundReasons?.length
                  ? caps.effectiveMustRefundReasons.join('、')
                  : '（未配置任何必退情形）'}
              </strong>
            </li>
          </ul>
          <p className="text-muted-foreground mt-3 text-copy-13">{caps.mustRefundEnforcementNote}</p>
          <p className="text-muted-foreground mt-1 text-copy-13">{caps.replyDeadlineNote}</p>
          <p className="text-muted-foreground mt-1 text-copy-13">{caps.ownerSourceNote}</p>
          <p className="text-muted-foreground mt-1 text-copy-13">{caps.platformMessageNote}</p>
        </SectionCard>
      )}

      <SectionCard
        title="受理一张售后请求"
        description="受理会同时落下三样：待处理售后单、政策判定凭据、责任人分配。三者在一个事务里，要么全成要么全不成。"
      >
        {submitError && (
          <div className="border-destructive-soft bg-destructive-soft text-destructive mb-4 flex items-center gap-2 rounded-lg border px-4 py-3">
            <TriangleAlert className="h-4 w-4 shrink-0" />
            <span className="text-copy-13">{submitError}</span>
          </div>
        )}

        {submitted && (
          <div className="border-border bg-muted/40 mb-4 rounded-lg border px-4 py-3">
            <p className="text-copy-13">
              受理成功：售后单{' '}
              <Link className="underline" href={`/aftersales/cases/${submitted.verdict.caseId}`}>
                {submitted.verdict.caseNo}
              </Link>{' '}
              已落库，判定结论
              <strong className="text-foreground">
                {eligibilityPresentation(submitted.verdict.eligibility).label}
              </strong>
              ，责任人
              <strong className="text-foreground">
                {submitted.ownerAssignment.ownerRef ?? '待认领'}
              </strong>
              （{ownerModeText(submitted.ownerAssignment.mode)}）。
            </p>
            <p className="text-muted-foreground mt-1 text-copy-13">{submitted.policyNote}</p>
          </div>
        )}

        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">关联订单 ID（必填）</span>
            <Input
              value={form.orderId}
              onChange={(e) => setForm({ ...form, orderId: e.target.value })}
              placeholder="订单 UUID，售后期以它的下单时刻起算"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">售后类型</span>
            <select
              className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
              value={form.caseType}
              onChange={(e) => setForm({ ...form, caseType: e.target.value })}
            >
              {CASE_TYPES.map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">原因分类（强制选择，不允许自由文本）</span>
            <select
              className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
              value={form.reasonCode}
              onChange={(e) => setForm({ ...form, reasonCode: e.target.value })}
            >
              {REASON_OPTIONS.map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">申请金额</span>
            <Input
              value={form.requestedAmount}
              onChange={(e) => setForm({ ...form, requestedAmount: e.target.value })}
              placeholder="如 88.00"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">币种</span>
            <Input
              value={form.currency}
              onChange={(e) => setForm({ ...form, currency: e.target.value })}
              placeholder="USD"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">申请数量</span>
            <Input
              value={form.requestedQuantity}
              onChange={(e) => setForm({ ...form, requestedQuantity: e.target.value })}
              placeholder="1"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">受理来源</span>
            <select
              className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
              value={form.intakeSource}
              onChange={(e) => setForm({ ...form, intakeSource: e.target.value })}
            >
              <option value="INTERNAL_ENTRY">内部录入</option>
              <option value="BUYER_MESSAGE">买家消息</option>
              <option value="PLATFORM_MESSAGE">平台消息</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">平台申请号（平台消息建议填，用于重投判重）</span>
            <Input
              value={form.intakeReference}
              onChange={(e) => setForm({ ...form, intakeReference: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">处理方案</span>
            <select
              className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
              value={form.handlingPlan}
              onChange={(e) => setForm({ ...form, handlingPlan: e.target.value })}
            >
              {PLAN_OPTIONS.map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">指定责任人（留空则按名册轮转自动派单）</span>
            <Input
              value={form.ownerRef}
              onChange={(e) => setForm({ ...form, ownerRef: e.target.value })}
              placeholder="留空 = 自动派单"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">买家诉求</span>
            <Input
              value={form.buyerRequest}
              onChange={(e) => setForm({ ...form, buyerRequest: e.target.value })}
            />
          </label>
        </div>

        {/* 平台强制必退只能由平台消息声明 —— 控件随受理来源禁用并说明原因。 */}
        <label className="mt-4 flex items-start gap-2">
          <input
            type="checkbox"
            className="mt-1"
            checked={form.platformMustRefund}
            disabled={form.intakeSource !== 'PLATFORM_MESSAGE'}
            onChange={(e) => setForm({ ...form, platformMustRefund: e.target.checked })}
          />
          <span className="text-copy-13">
            平台声明本单为强制必退
            <span className="text-muted-foreground block">
              只有受理来源是「平台消息」时才可勾选：受理人在内部单上勾这一项不构成平台声明，
              后端会拒。必退一旦成立就在数据库层锁定，重新判定也不能降级。
            </span>
          </span>
        </label>

        <div className="mt-4 flex gap-2">
          <Button onClick={() => void submitIntake()} disabled={!intakeReady || submitting}>
            {submitting ? '受理中…' : '受理'}
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setForm(EMPTY_INTAKE)}>
            重置
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="回复时限策略"
        description={
          caps?.replyDeadlineSource ??
          '平台给了时限就用平台的；没给则按租户策略推算。两档都没有时受理会直接失败。'
        }
      >
        {slaError && (
          <div className="border-destructive-soft bg-destructive-soft text-destructive mb-3 flex items-center gap-2 rounded-lg border px-4 py-3">
            <TriangleAlert className="h-4 w-4 shrink-0" />
            <span className="text-copy-13">{slaError}</span>
          </div>
        )}
        {sla ? (
          <p className="text-copy-13">
            当前生效档：平台{' '}
            <strong className="text-foreground">{sla.platformScope || '全平台兜底'}</strong>，
            回复时限 <strong className="text-foreground">{sla.replySlaHours} 小时</strong>
            {sla.resolveSlaHours ? `，处理时限 ${sla.resolveSlaHours} 小时` : '，未设处理时限'}，
            生效于 {formatTime(sla.effectiveFrom)}。
          </p>
        ) : (
          <p className="text-destructive text-copy-13">
            还没有配置任何生效中的回复时限策略 —— 现在受理会因为「拿不到回复时限」而失败，
            请先在下面配一档。
          </p>
        )}
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">平台档（留空 = 全平台兜底）</span>
            <Input
              value={slaScope}
              onChange={(e) => setSlaScope(e.target.value.toUpperCase())}
              placeholder="如 SHOPIFY"
              className="w-40"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">回复时限（小时）</span>
            <Input
              value={slaHours}
              onChange={(e) => setSlaHours(e.target.value)}
              className="w-28"
            />
          </label>
          <Button onClick={() => void saveSla()} disabled={slaBusy}>
            新开一档
          </Button>
          {sla && (
            <Button variant="outline" size="sm" onClick={() => void retireSla(sla.id)}>
              收口当前档
            </Button>
          )}
        </div>
        <p className="text-muted-foreground mt-2 text-copy-13">
          同一平台档同一时刻只能有一档生效，且档内容写定后不可改 —— 想改时限只能收口旧档再开新档。
        </p>
      </SectionCard>

      <SectionCard
        title="责任人名册"
        description={caps?.ownerSourceNote ?? '租户可维护的名册；自动派单按「在办单数 ÷ 权重」最少者优先。'}
      >
        {ownerError && (
          <div className="border-destructive-soft bg-destructive-soft text-destructive mb-3 flex items-center gap-2 rounded-lg border px-4 py-3">
            <TriangleAlert className="h-4 w-4 shrink-0" />
            <span className="text-copy-13">{ownerError}</span>
          </div>
        )}
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">责任人标识</span>
            <Input
              value={ownerRef}
              onChange={(e) => setOwnerRef(e.target.value)}
              className="w-48"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">权重（1~100）</span>
            <Input
              value={ownerWeight}
              onChange={(e) => setOwnerWeight(e.target.value)}
              className="w-24"
            />
          </label>
          <Button onClick={() => void addOwner()} disabled={ownerBusy || !ownerRef.trim()}>
            登记 / 更新
          </Button>
        </div>

        {roster.length === 0 ? (
          <p className="text-muted-foreground mt-4 text-copy-13">
            名册为空 —— 受理时责任人会是「待认领」，不会被静默指派给某个不存在的人。
          </p>
        ) : (
          <table className="mt-4 w-full text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b text-left">
                <th className="py-2">责任人</th>
                <th className="py-2">来源</th>
                <th className="py-2">权重</th>
                <th className="py-2">接新单</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {roster.map((row) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="py-2">
                    <span className="text-foreground">{row.ownerDisplayName || row.ownerRef}</span>
                    <span className="text-muted-foreground ml-1 text-copy-13">{row.ownerRef}</span>
                  </td>
                  <td className="py-2">{row.ownerSource}</td>
                  <td className="py-2">{row.weight}</td>
                  <td className="py-2">{row.acceptingNewCases ? '是' : '否'}</td>
                  <td className="py-2 text-right">
                    <Button variant="outline" size="sm" onClick={() => void toggleOwner(row)}>
                      {row.acceptingNewCases ? '暂停接单' : '恢复接单'}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-copy-13">受理结论</span>
            <select
              className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
              value={params.eligibility}
              onChange={(e) => setParams({ ...params, eligibility: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              <option value="ACCEPTED_MUST_REFUND">符合条件 · 必退</option>
              <option value="ACCEPTED">符合条件</option>
              <option value="OUT_OF_WINDOW_PENDING">超窗待裁定</option>
              <option value="REJECTED_OUT_OF_WINDOW">超窗不受理</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-copy-13">
            <input
              type="checkbox"
              checked={params.mustRefundOnly}
              onChange={(e) =>
                setParams({ ...params, mustRefundOnly: e.target.checked, page: 0 })
              }
            />
            只看必退
          </label>
          <label className="flex items-center gap-2 text-copy-13">
            <input
              type="checkbox"
              checked={params.currentOnly}
              onChange={(e) =>
                setParams({ ...params, currentOnly: e.target.checked, page: 0 })
              }
            />
            只看生效中的判定
          </label>
          <Input
            value={params.keyword}
            onChange={(e) => setParams({ ...params, keyword: e.target.value, page: 0 })}
            placeholder="售后号 / 平台申请号"
            className="w-56"
          />
          <select
            className="border-input bg-background rounded-md border px-3 py-2 text-copy-13"
            value={params.sort}
            onChange={(e) => setParams({ ...params, sort: e.target.value, page: 0 })}
          >
            <option value="MUST_REFUND_FIRST">必退优先</option>
            <option value="REPLIED_ASC">回复时限由近到远</option>
            <option value="REPLIED_DESC">回复时限由远到近</option>
            <option value="JUDGED_ASC">受理时刻升序</option>
          </select>
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-1 h-3 w-3" />
            刷新
          </Button>
        </div>
      </SectionCard>

      <SectionCard title="受理判定凭据" description={page?.sortSemantics ?? ''}>
        {loading && !page ? (
          <div className="text-muted-foreground p-6 text-center text-copy-13">加载中…</div>
        ) : (page?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有受理判定凭据"
            description="受理一张售后请求后，这里会出现它的四项判定结果与政策依据。"
          />
        ) : (
          <div className="flex flex-col gap-3">
            {page?.items.map((row) => (
              <VerdictRow
                key={row.verdictId}
                row={row}
                replyOverdue={overdueSet.has(row.caseId)}
                onRejudge={() => void rejudge(row.caseId)}
              />
            ))}
          </div>
        )}

        {page && page.totalPages > 1 && (
          <div className="mt-4 flex items-center gap-3">
            <Button
              variant="outline"
              size="sm"
              disabled={page.page <= 0}
              onClick={() => setParams({ ...params, page: page.page - 1 })}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-copy-13">
              第 {page.page + 1} / {page.totalPages} 页，共 {page.total} 条
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page.page + 1 >= page.totalPages}
              onClick={() => setParams({ ...params, page: page.page + 1 })}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

function VerdictRow({
  row,
  replyOverdue,
  onRejudge,
}: {
  row: AftersalesIntakeVerdictView;
  replyOverdue: boolean;
  onRejudge: () => void;
}) {
  const must = mustRefundPresentation(row.mustRefund, replyOverdue);
  const elig = eligibilityPresentation(row.eligibility);
  const windowDeadline = new Date(row.returnWindowDeadlineAt);
  const replyDue = new Date(row.replyDueAt);

  return (
    <div className="border-border rounded-lg border px-4 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <Link className="text-foreground underline" href={`/aftersales/cases/${row.caseId}`}>
          {row.caseNo}
        </Link>
        <span className={`badge ${mustRefundBadgeClass(row.mustRefund, replyOverdue)}`}>{must.badge}</span>
        <span className={`badge ${eligibilityBadgeClass(row.eligibility)}`}>{elig.label}</span>
        <span className="text-muted-foreground text-copy-13">
          {mustRefundBasisText(row.mustRefundBasis, row.mustRefundSituation)}
        </span>
        <Button variant="ghost" size="sm" className="ml-auto" onClick={onRejudge}>
          重新判定
        </Button>
      </div>

      <dl className="text-muted-foreground mt-2 grid gap-x-6 gap-y-1 text-copy-13 sm:grid-cols-2 lg:grid-cols-3">
        <div>
          <dt className="inline">售后期：</dt>
          <dd className="text-foreground inline">
            {windowMarginText(row.inReturnWindow, row.windowMarginHours)}（{row.policyReturnWindowDays} 天，
            {orderAnchorText(row.orderAnchorKind)}）
          </dd>
        </div>
        <div>
          <dt className="inline">回复时限：</dt>
          <dd className="text-foreground inline">
            {formatTime(row.replyDueAt)}
            {replyOverdue ? `（已超时 ${humanizeHours((replyDue.getTime() - Date.now()) / -3600000)}）` : ''}
            {' · '}
            {replyDueBasisText(row.replyDueBasis, row.replySlaHours)}
          </dd>
        </div>
        <div>
          <dt className="inline">处理方案：</dt>
          <dd className="text-foreground inline">
            {row.handlingPlan}
            {row.handlingPlanSource === 'MUST_REFUND_FORCED' ? '（必退强制）' : ''}
          </dd>
        </div>
        <div>
          <dt className="inline">责任人：</dt>
          <dd className="text-foreground inline">{ownerModeText(row.ownerAssignmentMode)}</dd>
        </div>
        <div>
          <dt className="inline">受理来源：</dt>
          <dd className="text-foreground inline">
            {row.intakeSource}
            {row.intakeReference ? ` · ${row.intakeReference}` : ''}
          </dd>
        </div>
        <div>
          <dt className="inline">政策档位：</dt>
          <dd className="text-foreground inline">
            {row.policySourceScope} · 冻结于 {formatTime(row.policyReadAt)}
          </dd>
        </div>
      </dl>

      {row.policyMustRefundReasons.length > 0 && (
        <p className="text-muted-foreground mt-2 text-copy-13">
          冻结的必退词表（{row.policyMustRefundReasons.join('、')}）
        </p>
      )}
    </div>
  );
}
