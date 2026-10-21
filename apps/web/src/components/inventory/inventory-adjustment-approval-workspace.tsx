'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { CheckCheck, Gavel, ListChecks, RefreshCw, Scale, ShieldCheck } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  InventoryAdjustmentApprovalApprover,
  InventoryAdjustmentApprovalCapabilities,
  InventoryAdjustmentApprovalPendingPage,
  InventoryAdjustmentApprovalRule,
  InventoryAdjustmentApprovalValuationPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  approvalCaseStatusLabel,
  approvalCaseSummary,
  approvalCaseTone,
  approvalErrorText,
  approvalRecordActionLabel,
  amountText,
  approveProblem,
  approverProblem,
  batchApproveAdjustments,
  batchItemText,
  batchRejectAdjustments,
  batchSummary,
  createApprovalRule,
  fetchApprovalApprovers,
  fetchApprovalCapabilities,
  fetchApprovalRules,
  fetchApprovalValuations,
  fetchPendingApprovals,
  approveAdjustmentOrder,
  qtyText,
  registerApprovalApprover,
  rejectAdjustmentOrder,
  rejectProblem,
  ruleMatchLabel,
  ruleProblem,
  setApprovalApproverStatus,
  setApprovalRuleStatus,
  submitProblem,
  tierLabel,
  valuationStatusLabel,
} from './inventory-adjustment-approval-model';
import { useIdentity } from '../iam/identity-shell';

type Tab = 'pending' | 'rules' | 'approvers' | 'valuations';

const REASON_CODES = [
  { value: 'ANY', label: '全部类型' },
  { value: 'OVERAGE', label: '盘盈' },
  { value: 'SHORTAGE', label: '盘亏' },
  { value: 'DAMAGE', label: '损坏' },
  { value: 'SCRAP', label: '报废' },
  { value: 'MISSHIP_CORRECTION', label: '错发纠正' },
  { value: 'PLATFORM_VARIANCE', label: '平台仓差异（不可用）' },
];

/**
 * 7.4.1.2 调整审批工作区。
 *
 * <p>蓝图：「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
 * 以及：「**库存调整是舞弊高发区**（无审批的调整 = 任意改账），阈值审批是必要控制。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**阈值闸门是否启用**要说出来：没配规则时阈值不参与，但基础管控（提交人≠审批人）仍然生效。
 *   <li>**批准不改余额**，只有 7.4.1.1 的「过账」那一步改 —— 每个按钮旁边都写着。
 *   <li>**审批人数要显示「已批 N/M」**：没凑齐就批不动，用户看不到这个就会反复点。
 *   <li>**不该由我批的也显示出来**，标成不可批并写清原因（直接不显示会让人以为这单没人管）。
 *   <li>**申报金额是申报值**（warehouse 没有成本口径）—— 界面上不假装它是系统算出来的。
 * </ul>
 */
export function InventoryAdjustmentApprovalWorkspace() {
  const { session } = useIdentity();
  const [tab, setTab] = useState<Tab>('pending');

  const [capabilities, setCapabilities] = useState<InventoryAdjustmentApprovalCapabilities | null>(
    null,
  );
  const [pending, setPending] = useState<InventoryAdjustmentApprovalPendingPage | null>(null);
  const [rules, setRules] = useState<InventoryAdjustmentApprovalRule[]>([]);
  const [approvers, setApprovers] = useState<InventoryAdjustmentApprovalApprover[]>([]);
  const [valuations, setValuations] = useState<InventoryAdjustmentApprovalValuationPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; title: string; body: string } | null>(
    null,
  );

  const [selected, setSelected] = useState<string[]>([]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [batchNote, setBatchNote] = useState('');
  const [busy, setBusy] = useState(false);

  const [ruleForm, setRuleForm] = useState({
    ruleNo: '',
    ruleName: '',
    reasonCode: 'ANY',
    currency: 'ANY',
    minAmount: '0',
    maxAmount: '',
    minQty: '0',
    maxQty: '',
    requiredTier: 'L1',
    requiredApprovals: '1',
  });
  const [approverForm, setApproverForm] = useState({
    userId: session.userId,
    userName: '',
    approvalTier: 'L1',
    currency: 'ANY',
    minAmount: '0',
    maxAmount: '',
    maxQty: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, pend, ruleList, approverList, valuationPage] = await Promise.all([
        fetchApprovalCapabilities(),
        fetchPendingApprovals(),
        fetchApprovalRules(),
        fetchApprovalApprovers(),
        fetchApprovalValuations(),
      ]);
      setCapabilities(caps);
      setPending(pend);
      setRules(ruleList);
      setApprovers(approverList);
      setValuations(valuationPage);
    } catch (e) {
      setError(approvalErrorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const pendingItems = useMemo(() => pending?.items ?? [], [pending]);
  const approvableCount = useMemo(
    () => pendingItems.filter((item) => item.approvable).length,
    [pendingItems],
  );
  const toggle = (orderId: string) =>
    setSelected((current) =>
      current.includes(orderId) ? current.filter((id) => id !== orderId) : [...current, orderId],
    );

  const run = async (label: string, work: () => Promise<string>) => {
    setBusy(true);
    setNotice(null);
    try {
      const body = await work();
      setNotice({ tone: 'success', title: `${label}完成`, body });
    } catch (e) {
      setNotice({ tone: 'danger', title: `${label}失败`, body: approvalErrorText(e) });
    } finally {
      setBusy(false);
      await load();
    }
  };

  // ---------------------------------------------------------- 阈值规则

  const submitRule = async () => {
    const problem = ruleProblem(ruleForm);
    if (problem) {
      setNotice({ tone: 'danger', title: '规则没保存', body: problem });
      return;
    }
    await run('保存规则', async () => {
      await createApprovalRule({
        ruleNo: ruleForm.ruleNo.trim(),
        ruleName: ruleForm.ruleName.trim(),
        reasonCode: ruleForm.reasonCode,
        currency: ruleForm.currency.trim().toUpperCase(),
        minAmount: Number(ruleForm.minAmount || 0),
        maxAmount: ruleForm.maxAmount.trim() === '' ? null : Number(ruleForm.maxAmount),
        minQty: Number(ruleForm.minQty || 0),
        maxQty: ruleForm.maxQty.trim() === '' ? null : Number(ruleForm.maxQty),
        requiredTier: ruleForm.requiredTier,
        requiredApprovals: Number(ruleForm.requiredApprovals),
        // 契约里这两个字段是「可为 null」而不是「可省略」（exactOptionalPropertyTypes），
        // 所以必须**显式**给值，不能靠不写来表达「没填」。
        priority: 100,
        note: '',
      });
      setRuleForm({ ...ruleForm, ruleNo: '', ruleName: '' });
      return `规则「${ruleForm.ruleNo.trim()}」已保存`;
    });
  };

  const submitApprover = async () => {
    const problem = approverProblem(approverForm);
    if (problem) {
      setNotice({ tone: 'danger', title: '授权没登记', body: problem });
      return;
    }
    await run('登记授权', async () => {
      await registerApprovalApprover({
        userId: approverForm.userId.trim(),
        userName: approverForm.userName.trim(),
        approvalTier: approverForm.approvalTier,
        currency: approverForm.currency.trim().toUpperCase(),
        minAmount: Number(approverForm.minAmount || 0),
        maxAmount: approverForm.maxAmount.trim() === '' ? null : Number(approverForm.maxAmount),
        maxQty: approverForm.maxQty.trim() === '' ? null : Number(approverForm.maxQty),
        // 同上：契约为「可为 null」，必须显式给。
        note: '',
      });
      return '审批人授权已登记';
    });
  };

  // ---------------------------------------------------------- 审批动作

  const approveOne = async (orderId: string, note: string) => {
    const problem = approveProblem(note);
    if (problem) {
      setNotice({ tone: 'danger', title: '批准没提交', body: problem });
      return;
    }
    await run('批准', async () => {
      const detail = await approveAdjustmentOrder(orderId, note.trim());
      return `${detail.approvalCase.caseNo}：已批 ${detail.approvalCase.approvedCount}/${detail.approvalCase.requiredApprovals} 人`;
    });
  };

  const rejectOne = async (orderId: string, note: string) => {
    const problem = rejectProblem(note);
    if (problem) {
      setNotice({ tone: 'danger', title: '驳回没提交', body: problem });
      return;
    }
    await run('驳回', async () => {
      await rejectAdjustmentOrder(orderId, note.trim());
      return '已驳回，提交的人会看到你的意见';
    });
  };

  const runBatch = async (approve: boolean) => {
    if (selected.length === 0) {
      setNotice({ tone: 'danger', title: '没选单据', body: '批量处理至少要选中一张调整单' });
      return;
    }
    if (!approve && rejectProblem(batchNote)) {
      setNotice({ tone: 'danger', title: '驳回没提交', body: rejectProblem(batchNote) ?? '' });
      return;
    }
    await run(approve ? '批量批准' : '批量驳回', async () => {
      const result = approve
        ? await batchApproveAdjustments({ orderIds: selected, note: batchNote })
        : await batchRejectAdjustments({ orderIds: selected, note: batchNote });
      setSelected([]);
      const lines = result.items.map((item) => `· ${batchItemText(item)}`).join('\n');
      return `${batchSummary(result)}\n${lines}`;
    });
  };

  // ---------------------------------------------------------- 渲染

  return (
    <div className="space-y-4">
      <PageHeader
        title="调整审批"
        description="按金额/数量阈值审批库存调整单。库存调整是舞弊高发区，阈值审批是必要控制。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}

      {notice ? (
        <Notice tone={notice.tone} title={notice.title}>
          <pre className="whitespace-pre-wrap text-xs">{notice.body}</pre>
        </Notice>
      ) : null}

      {capabilities ? (
        <div className="grid gap-3 md:grid-cols-3">
          <StatCard
            label="阈值闸门"
            value={capabilities.policy.thresholdEnabled ? '已启用' : '未启用'}
            polarity={capabilities.policy.thresholdEnabled ? 'positive' : 'neutral'}
            icon={<ShieldCheck className="h-4 w-4" />}
          />
          <StatCard
            label="生效中的阈值规则"
            value={capabilities.policy.activeRuleCount}
            unit="条"
            icon={<Scale className="h-4 w-4" />}
          />
          <StatCard
            label="待批完的审批单"
            value={capabilities.policy.liveCaseCount}
            unit="条"
            icon={<Gavel className="h-4 w-4" />}
          />
        </div>
      ) : null}

      {capabilities ? (
        <Notice tone={capabilities.policy.thresholdEnabled ? 'info' : 'warning'} title="阈值闸门状态">
          {capabilities.policy.note}
        </Notice>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {(
          [
            { key: 'pending', label: '待我审批', icon: <ListChecks className="h-4 w-4" /> },
            { key: 'rules', label: '阈值规则', icon: <Scale className="h-4 w-4" /> },
            { key: 'approvers', label: '审批人授权', icon: <ShieldCheck className="h-4 w-4" /> },
            { key: 'valuations', label: '会计影响', icon: <Gavel className="h-4 w-4" /> },
          ] as const
        ).map((entry) => (
          <Button
            key={entry.key}
            size="sm"
            variant={tab === entry.key ? 'default' : 'outline'}
            onClick={() => setTab(entry.key)}
          >
            {entry.icon}
            <span className="ml-1">{entry.label}</span>
          </Button>
        ))}
      </div>

      {loading ? (
        <SectionCard title="加载中">
          <p className="text-muted-foreground text-sm">正在读取审批数据…</p>
        </SectionCard>
      ) : null}

      {!loading && tab === 'pending' ? (
        <>
          <SectionCard
            title="待我审批"
            description="每一行都标了「此刻你能不能批」与原因：不该由你批的也列出来，是为了不让它看起来像没人管。"
            extra={
              <span className="text-muted-foreground text-xs">
                共 {pending?.total ?? 0} 条 · 其中 {approvableCount} 条可由我批
              </span>
            }
          >
            {pendingItems.length === 0 ? (
              <EmptyState
                title="没有待审批的调整单"
                description="待审的调整单会出现在这里。调整单要先在「调整单管理」里提交待审。"
              />
            ) : (
              <ul className="space-y-3">
                {pendingItems.map((item) => (
                  <li key={item.approvalCase.id} className="border-border rounded border p-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-medium">{item.orderCode}</span>
                          <Badge tone={approvalCaseTone(item.approvalCase.status)}>
                            {approvalCaseStatusLabel(item.approvalCase.status)}
                          </Badge>
                          <Badge tone="info">{tierLabel(item.approvalCase.requiredTier)}</Badge>
                          <Badge tone="neutral">
                            已批 {item.approvalCase.approvedCount}/{item.approvalCase.requiredApprovals} 人
                          </Badge>
                        </div>
                        <p className="text-muted-foreground mt-1 text-xs">
                          {ruleMatchLabel(item.approvalCase)} · 申报金额 {amountText(item.approvalCase.orderAmount)}{' '}
                          {item.approvalCase.currency} · 调整量 {qtyText(item.approvalCase.orderQty)} ·{' '}
                          {item.lineCount} 行 · 净调整 {qtyText(item.netQty)} · 原因{' '}
                          {item.reasonLabel || item.reasonCode}
                        </p>
                        <p className="mt-1 text-xs">
                          {item.approvable ? (
                            <span className="text-success">可以批</span>
                          ) : (
                            <span className="text-warning">{item.approvableReason}</span>
                          )}
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${item.orderCode}`}
                          checked={selected.includes(item.approvalCase.orderId)}
                          onChange={() => toggle(item.approvalCase.orderId)}
                        />
                        <span className="text-muted-foreground text-xs">批量处理</span>
                      </div>
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Input
                        className="max-w-md"
                        placeholder="审批意见（驳回必填；批准建议写清依据）"
                        id={`note-${item.approvalCase.orderId}`}
                        value={notes[item.approvalCase.orderId] ?? ''}
                        onChange={(event) =>
                          setNotes((current) => ({
                            ...current,
                            [item.approvalCase.orderId]: event.target.value,
                          }))
                        }
                      />
                      <Button
                        size="sm"
                        variant="success"
                        disabled={busy || !item.approvable}
                        onClick={() =>
                          void approveOne(
                            item.approvalCase.orderId,
                            notes[item.approvalCase.orderId] ?? '',
                          )
                        }
                      >
                        批准
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        disabled={busy || !item.approvable}
                        onClick={() =>
                          void rejectOne(
                            item.approvalCase.orderId,
                            notes[item.approvalCase.orderId] ?? '',
                          )
                        }
                      >
                        驳回
                      </Button>
                    </div>
                    <p className="text-muted-foreground mt-2 text-xs">
                      批准**不会**改库存余额 —— 它只是允许入账，入账是 7.4.1.1 的「过账」那一步。
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="批量审批 / 批量驳回" description="逐条给结论：一张失败不影响其它张，失败原因逐条列出，不静默丢弃。">
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="batch-note" label="审批意见">
                <Input
                  id="batch-note"
                  className="min-w-72"
                  placeholder="批量驳回必须写意见"
                  value={batchNote}
                  onChange={(event) => setBatchNote(event.target.value)}
                />
              </FormField>
              <Button size="sm" variant="success" disabled={busy} onClick={() => void runBatch(true)}>
                <CheckCheck className="mr-1 h-4 w-4" />
                批量批准（{selected.length}）
              </Button>
              <Button size="sm" variant="destructive" disabled={busy} onClick={() => void runBatch(false)}>
                批量驳回（{selected.length}）
              </Button>
            </div>
          </SectionCard>
        </>
      ) : null}

      {!loading && tab === 'rules' ? (
        <>
          <SectionCard title="阈值规则" description="（调整单类型, 币种, 金额区间, 数量区间）⇒ 要求的审批层级与人数。区间是半开区间 [下限, 上限)。">
            {rules.length === 0 ? (
              <EmptyState
                title="还没有阈值规则"
                description="没有生效规则时阈值闸门未启用（基础管控仍然生效）：提交人≠审批人、必填理由、状态机、入账闸门。"
              />
            ) : (
              <ul className="space-y-2">
                {rules.map((rule) => (
                  <li key={rule.id} className="border-border flex flex-wrap items-center gap-2 rounded border p-2">
                    <span className="text-sm font-medium">{rule.ruleNo}</span>
                    <span className="text-muted-foreground text-xs">{rule.ruleName}</span>
                    <Badge tone="neutral">
                      {rule.reasonCode === 'ANY' ? '全部类型' : rule.reasonCode} · {rule.currency}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      金额 [{amountText(rule.minAmount)}
                      {rule.maxAmount === null ? ', ∞)' : `, ${amountText(rule.maxAmount)})`} · 数量 [
                      {qtyText(rule.minQty)}
                      {rule.maxQty === null ? ', ∞)' : `, ${qtyText(rule.maxQty)})`} ·{' '}
                      {tierLabel(rule.requiredTier)} · {rule.requiredApprovals} 人
                    </span>
                    <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {rule.status === 'ACTIVE' ? '生效中' : '已停用'}
                    </Badge>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void run(rule.status === 'ACTIVE' ? '停用规则' : '启用规则', async () => {
                          await setApprovalRuleStatus(
                            rule.id,
                            rule.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
                          );
                          return `规则「${rule.ruleNo}」已${rule.status === 'ACTIVE' ? '停用' : '启用'}`;
                        })
                      }
                    >
                      {rule.status === 'ACTIVE' ? '停用' : '启用'}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="新建阈值规则">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="rule-no" label="规则号" required>
                <Input
                  id="rule-no"
                  value={ruleForm.ruleNo}
                  onChange={(event) => setRuleForm({ ...ruleForm, ruleNo: event.target.value })}
                />
              </FormField>
              <FormField id="rule-name" label="规则名" required>
                <Input
                  id="rule-name"
                  value={ruleForm.ruleName}
                  onChange={(event) => setRuleForm({ ...ruleForm, ruleName: event.target.value })}
                />
              </FormField>
              <FormField id="rule-reason" label="适用调整单类型">
                <select
                  id="rule-reason"
                  className="border-control-border h-9 w-full rounded border bg-card px-2 text-sm"
                  value={ruleForm.reasonCode}
                  onChange={(event) => setRuleForm({ ...ruleForm, reasonCode: event.target.value })}
                >
                  {REASON_CODES.map((code) => (
                    <option key={code.value} value={code.value}>
                      {code.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="rule-currency" label="币种" hint="ANY = 不限币种">
                <Input
                  id="rule-currency"
                  value={ruleForm.currency}
                  onChange={(event) => setRuleForm({ ...ruleForm, currency: event.target.value })}
                />
              </FormField>
              <FormField id="rule-min-amount" label="金额下限（含）">
                <Input
                  id="rule-min-amount"
                  value={ruleForm.minAmount}
                  onChange={(event) => setRuleForm({ ...ruleForm, minAmount: event.target.value })}
                />
              </FormField>
              <FormField id="rule-max-amount" label="金额上限（不含，留空 = 无上限）">
                <Input
                  id="rule-max-amount"
                  value={ruleForm.maxAmount}
                  onChange={(event) => setRuleForm({ ...ruleForm, maxAmount: event.target.value })}
                />
              </FormField>
              <FormField id="rule-min-qty" label="数量下限（含）" hint="量纲 = 绝对调整量">
                <Input
                  id="rule-min-qty"
                  value={ruleForm.minQty}
                  onChange={(event) => setRuleForm({ ...ruleForm, minQty: event.target.value })}
                />
              </FormField>
              <FormField id="rule-max-qty" label="数量上限（不含，留空 = 无上限）">
                <Input
                  id="rule-max-qty"
                  value={ruleForm.maxQty}
                  onChange={(event) => setRuleForm({ ...ruleForm, maxQty: event.target.value })}
                />
              </FormField>
              <FormField id="rule-tier" label="要求的审批层级">
                <select
                  id="rule-tier"
                  className="border-control-border h-9 w-full rounded border bg-card px-2 text-sm"
                  value={ruleForm.requiredTier}
                  onChange={(event) => setRuleForm({ ...ruleForm, requiredTier: event.target.value })}
                >
                  {['L1', 'L2', 'L3'].map((tier) => (
                    <option key={tier} value={tier}>
                      {tier}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="rule-approvals" label="要求的审批人数">
                <Input
                  id="rule-approvals"
                  value={ruleForm.requiredApprovals}
                  onChange={(event) =>
                    setRuleForm({ ...ruleForm, requiredApprovals: event.target.value })
                  }
                />
              </FormField>
            </div>
            <Button className="mt-3" size="sm" disabled={busy} onClick={() => void submitRule()}>
              保存规则
            </Button>
          </SectionCard>
        </>
      ) : null}

      {!loading && tab === 'approvers' ? (
        <>
          <SectionCard
            title="审批人授权"
            description="层级 + 金额授权区间 + 数量授权上限。缺了区间就是「这个人什么都能批」—— 那不叫授权，叫没有闸门。"
          >
            {approvers.length === 0 ? (
              <EmptyState
                title="还没有审批人授权"
                description="没有授权的人批不了任何单：审批人要先登记层级与授权区间。"
              />
            ) : (
              <ul className="space-y-2">
                {approvers.map((approver) => (
                  <li key={approver.id} className="border-border flex flex-wrap items-center gap-2 rounded border p-2">
                    <span className="text-sm font-medium">{approver.userName || approver.userId}</span>
                    <Badge tone="info">{tierLabel(approver.approvalTier)}</Badge>
                    <span className="text-muted-foreground text-xs">
                      {approver.currency} 金额 [{amountText(approver.minAmount)}
                      {approver.maxAmount === null ? ', ∞)' : `, ${amountText(approver.maxAmount)})`} · 数量上限{' '}
                      {approver.maxQty === null ? '不限' : qtyText(approver.maxQty)}
                    </span>
                    <Badge tone={approver.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {approver.status === 'ACTIVE' ? '生效中' : '已停用'}
                    </Badge>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          approver.status === 'ACTIVE' ? '停用授权' : '启用授权',
                          async () => {
                            await setApprovalApproverStatus(
                              approver.id,
                              approver.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
                            );
                            return '授权状态已更新';
                          },
                        )
                      }
                    >
                      {approver.status === 'ACTIVE' ? '停用' : '启用'}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="登记审批人授权">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="approver-user" label="审批人用户 ID" required>
                <Input
                  id="approver-user"
                  value={approverForm.userId}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, userId: event.target.value })
                  }
                />
              </FormField>
              <FormField id="approver-name" label="姓名">
                <Input
                  id="approver-name"
                  value={approverForm.userName}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, userName: event.target.value })
                  }
                />
              </FormField>
              <FormField id="approver-tier" label="审批层级">
                <select
                  id="approver-tier"
                  className="border-control-border h-9 w-full rounded border bg-card px-2 text-sm"
                  value={approverForm.approvalTier}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, approvalTier: event.target.value })
                  }
                >
                  {['L1', 'L2', 'L3'].map((tier) => (
                    <option key={tier} value={tier}>
                      {tier}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="approver-currency" label="币种" hint="ANY = 不限币种">
                <Input
                  id="approver-currency"
                  value={approverForm.currency}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, currency: event.target.value })
                  }
                />
              </FormField>
              <FormField id="approver-min-amount" label="金额授权下限（含）">
                <Input
                  id="approver-min-amount"
                  value={approverForm.minAmount}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, minAmount: event.target.value })
                  }
                />
              </FormField>
              <FormField id="approver-max-amount" label="金额授权上限（不含，留空 = 不限）">
                <Input
                  id="approver-max-amount"
                  value={approverForm.maxAmount}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, maxAmount: event.target.value })
                  }
                />
              </FormField>
              <FormField id="approver-max-qty" label="数量授权上限（留空 = 不限）">
                <Input
                  id="approver-max-qty"
                  value={approverForm.maxQty}
                  onChange={(event) =>
                    setApproverForm({ ...approverForm, maxQty: event.target.value })
                  }
                />
              </FormField>
            </div>
            <Button className="mt-3" size="sm" disabled={busy} onClick={() => void submitApprover()}>
              登记授权
            </Button>
          </SectionCard>
        </>
      ) : null}

      {!loading && tab === 'valuations' ? (
        <SectionCard
          title="调整的会计影响"
          description="登记额与生效额分两列：12 财务服务在本仓不存在，这里只是 warehouse 侧自有事实，不跨库读 finance。"
        >
          {!valuations || valuations.items.length === 0 ? (
            <EmptyState
              title="没有登记过调整估值"
              description="在「调整单管理」里提交待审时申报金额，这里就会出现登记额；调整单过账后生效额才等于登记额。"
            />
          ) : (
            <ul className="space-y-2">
              {valuations.items.map((item) => (
                <li key={item.id} className="border-border flex flex-wrap items-center gap-2 rounded border p-2">
                  <span className="text-sm font-medium">{item.orderCode}</span>
                  <Badge tone={item.status === 'EFFECTIVE' ? 'success' : 'neutral'}>
                    {valuationStatusLabel(item.status)}
                  </Badge>
                  <span className="text-muted-foreground text-xs">
                    登记额 {amountText(item.registeredAmount)} {item.currency} · 生效额{' '}
                    {amountText(item.effectiveAmount)} {item.currency}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力与边界" description="本模块真的能做什么、哪些归别的模块。">
          <ul className="space-y-2">
            {capabilities.capabilities.map((capability) => (
              <li key={capability.key} className="text-sm">
                <span className="font-medium">{capability.label}</span>{' '}
                <Badge tone={capability.available ? 'success' : 'neutral'}>
                  {capability.available ? '可用' : '不可用'}
                </Badge>{' '}
                <span className="text-muted-foreground text-xs">归属：{capability.ownerModule}</span>
                <p className="text-muted-foreground mt-1 text-xs">{capability.note}</p>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}
