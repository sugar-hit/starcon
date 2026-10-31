'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  ClipboardCheck,
  FileWarning,
  ListChecks,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Timer,
} from 'lucide-react';
import {
  DECISIONS,
  EMPTY_EVALUATE,
  EMPTY_POLICY,
  EMPTY_SIGNAL,
  ESCALATION_STATUSES,
  POLICY_STATUSES,
  SIGNAL_KINDS,
  checkSummary,
  createSignal,
  decisionLabel,
  decisionTone,
  disablePolicy,
  disableSignal,
  escalationActionLabel,
  evaluateDecision,
  executeEscalation,
  fetchCapabilities,
  fetchDecision,
  fetchDecisions,
  fetchEscalations,
  fetchOverview,
  fetchPolicies,
  fetchSignals,
  formatDateTime,
  formatMoney,
  parseSignalCodes,
  policyStatusLabel,
  routingSummary,
  savePolicy,
  scanEscalations,
  signalKindLabel,
  signalKindTone,
  upstreamChannelHint,
  validateEvaluateForm,
  validatePolicyForm,
  validateSignalForm,
  type EvaluateForm,
  type PolicyForm,
  type SignalForm,
  type Tone,
} from './auto-approval-model';
import type {
  AutoApprovalCapabilities,
  AutoApprovalDecisionRow,
  AutoApprovalDecisionView,
  AutoApprovalEscalationRow,
  AutoApprovalOverview,
  AutoApprovalPolicyRow,
  AutoApprovalSignalRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.3.3.1「自动审批」工作区（卷 18 前端入口 `/integrations/auto-approvals`）。
 *
 * <p>🔴 页面上的六条纪律：
 *
 * 1. **「自动通过」必须有据可查**。每条决策都带**逐条判定**与**当时的规则快照**，
 *    页面把「哪一条不成立」原样显示出来 —— 只有一个「已自动通过」的绿标签，
 *    等于让人相信一台没人能解释的机器。
 * 2. **「自动驳回」与「走人工」必须一眼分得开**。驳回只发生在**确定性违规**上；
 *    主观风险（风险偏高）走人工，页面**不能**把它显示成「已驳回」。
 * 3. **「上游没有程序化决策通道」写在最上面**。自动决策**不产生待办**，
 *    必须走人工的单据才是真的提交给了上游人工审批。
 * 4. **金额分层路由显示的是命中哪一档**（区间口径与上游一致：左闭右开），
 *    不是「大概按金额算的」。
 * 5. **提交上游失败要说失败**。决策台账里那一行标着「提交上游失败（仍需人工）」，
 *    页面**不**把它显示成「已提交」。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 */

type Tab = 'policies' | 'signals' | 'decisions' | 'escalations' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const selectClass =
  'border-border bg-background text-foreground h-9 w-full rounded-md border px-2 text-sm';

export function AutoApprovalWorkspace() {
  const [tab, setTab] = useState<Tab>('policies');

  const [capabilities, setCapabilities] = useState<AutoApprovalCapabilities | null>(null);
  const [overview, setOverview] = useState<AutoApprovalOverview | null>(null);
  const [policies, setPolicies] = useState<AutoApprovalPolicyRow[]>([]);
  const [signals, setSignals] = useState<AutoApprovalSignalRow[]>([]);
  const [decisions, setDecisions] = useState<AutoApprovalDecisionRow[]>([]);
  const [escalations, setEscalations] = useState<AutoApprovalEscalationRow[]>([]);
  const [detail, setDetail] = useState<AutoApprovalDecisionView | null>(null);

  const [documentTypeCode, setDocumentTypeCode] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [decisionFilter, setDecisionFilter] = useState('');
  const [escalationStatus, setEscalationStatus] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [policyForm, setPolicyForm] = useState<PolicyForm>(EMPTY_POLICY);
  const [signalForm, setSignalForm] = useState<SignalForm>(EMPTY_SIGNAL);
  const [evaluateForm, setEvaluateForm] = useState<EvaluateForm>(EMPTY_EVALUATE);

  const filtered = useMemo(
    () => Boolean(documentTypeCode || currencyCode || status || keyword.trim() || decisionFilter),
    [documentTypeCode, currencyCode, status, keyword, decisionFilter],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ov, policyRows, signalRows, decisionRows, escalationRows] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchPolicies({
          documentTypeCode: documentTypeCode.trim() || undefined,
          currencyCode: currencyCode.trim() || undefined,
          status: status || undefined,
          keyword: keyword.trim() || undefined,
        }),
        fetchSignals({ keyword: keyword.trim() || undefined }),
        fetchDecisions({
          decision: decisionFilter || undefined,
          keyword: keyword.trim() || undefined,
        }),
        fetchEscalations({ status: escalationStatus || undefined }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setPolicies(policyRows);
      setSignals(signalRows);
      setDecisions(decisionRows);
      setEscalations(escalationRows);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [documentTypeCode, currencyCode, status, keyword, decisionFilter, escalationStatus]);

  useEffect(() => {
    void load();
  }, [load]);

  // ---------------------------------------------------------------- 写入

  const submitPolicy = async () => {
    const problem = validatePolicyForm(policyForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      await savePolicy({
        ruleCode: policyForm.ruleCode.trim(),
        ruleName: policyForm.ruleName.trim(),
        documentTypeCode: policyForm.documentTypeCode.trim(),
        currencyCode: policyForm.currencyCode.trim(),
        autoApproveMaxAmount: policyForm.autoApproveMaxAmount.trim()
          ? Number(policyForm.autoApproveMaxAmount)
          : null,
        amountHardLimit: policyForm.amountHardLimit.trim()
          ? Number(policyForm.amountHardLimit)
          : null,
        upstreamFlowId: policyForm.upstreamFlowId.trim() || null,
        status: policyForm.status,
        priority: policyForm.priority.trim() ? Number(policyForm.priority) : null,
        remark: policyForm.remark.trim(),
      });
      setPolicyForm(EMPTY_POLICY);
      setNotice('自动审批规则已登记。');
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  };

  const submitSignal = async () => {
    const problem = validateSignalForm(signalForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      await createSignal({
        signalCode: signalForm.signalCode.trim(),
        signalName: signalForm.signalName.trim(),
        signalKind: signalForm.signalKind,
        description: signalForm.description.trim(),
      });
      setSignalForm(EMPTY_SIGNAL);
      setNotice('风险信号码已登记。');
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  };

  const submitEvaluate = async () => {
    const problem = validateEvaluateForm(evaluateForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      const result = await evaluateDecision({
        documentTypeCode: evaluateForm.documentTypeCode.trim(),
        documentRef: evaluateForm.documentRef.trim(),
        subjectTitle: evaluateForm.subjectTitle.trim() || null,
        sourceSystem: evaluateForm.sourceSystem.trim(),
        currencyCode: evaluateForm.currencyCode.trim() || null,
        amount: evaluateForm.amount.trim() ? Number(evaluateForm.amount) : null,
        supplierId: evaluateForm.supplierId.trim() || null,
        supplierVerified: evaluateForm.supplierVerified,
        riskSignals: parseSignalCodes(evaluateForm.riskSignals).map((code) => ({
          code,
          detail: '',
        })),
      });
      setEvaluateForm(EMPTY_EVALUATE);
      setNotice(`判定结果：${decisionLabel(result.decision)} · ${result.reasonMessage}`);
      setDetail(result);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '判定失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const runScan = async () => {
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      const result = await scanEscalations();
      setNotice(result.note);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '扫描失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const runExecute = async (id: string) => {
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      const result = await executeEscalation(id);
      setNotice(`升级执行结果：${result.status} · ${result.executionDetail}`);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '执行失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const openDetail = async (id: string) => {
    setFormError(null);
    try {
      setDetail(await fetchDecision(id));
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '读取决策详情失败');
    }
  };

  // ---------------------------------------------------------------- 渲染

  return (
    <div className="space-y-4">
      <PageHeader
        title="自动审批"
        description="自动通过低风险小额单据、自动驳回明显不合规单据、金额分层路由审批人、超时自动升级 —— 只处理「明确安全」的场景，其余走人工。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <RefreshCw className="size-4" />
            )}
            刷新
          </Button>
        }
      />

      {/* 🔴 边界必须写在最上面：不知道这条边界的人会以为系统能替人审批。 */}
      <Notice tone="info" title="这条模块的边界">
        <p className="leading-relaxed">
          {upstreamChannelHint(capabilities) ||
            '上游 17.4 审批没有程序化通过/驳回通道：自动审批只做「判定 + 记录 + 转交」，审批动作仍在上游由人来按。'}
        </p>
        <p className="mt-2 leading-relaxed">
          {capabilities?.decisionOwnership ??
            '自动审批决策（结论 + 决策依据快照）归本模块；审批实例与审批动作归上游 17.4。'}
        </p>
      </Notice>

      {error && (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="生效规则" value={overview.activePolicyCount} unit="条" />
          <StatCard label="自动通过" value={overview.autoApprovedCount} unit="单" />
          <StatCard label="自动驳回" value={overview.autoRejectedCount} unit="单" />
          <StatCard label="走人工" value={overview.manualRequiredCount} unit="单" />
        </div>
      )}

      <Segmented
        aria-label="自动审批视图"
        value={tab}
        onChange={(value) => setTab(value as Tab)}
        options={[
          { value: 'policies', label: '规则配置' },
          { value: 'signals', label: '风险信号码表' },
          { value: 'decisions', label: '决策台账' },
          { value: 'escalations', label: '超时升级' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {tab === 'policies' && (
        <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
          <SectionCard
            title="自动审批规则配置"
            description="一个小额上限（超过它走人工）+ 一个硬上限（越过它才是金额越界）；上游审批流是只读引用。"
            extra={<Badge tone="info">{policies.length} 条</Badge>}
          >
            <div className="mb-4 grid gap-3 sm:grid-cols-4">
              <FormField id="policy-document-type" label="单据类型">
                <Input
                  id="policy-document-type"
                  value={documentTypeCode}
                  onChange={(e) => setDocumentTypeCode(e.target.value)}
                  placeholder="PURCHASE_ORDER"
                />
              </FormField>
              <FormField id="policy-currency" label="币种">
                <Input
                  id="policy-currency"
                  value={currencyCode}
                  onChange={(e) => setCurrencyCode(e.target.value.toUpperCase())}
                  placeholder="USD"
                />
              </FormField>
              <FormField id="policy-status" label="状态">
                <select
                  id="policy-status"
                  className={selectClass}
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  {POLICY_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {policyStatusLabel(s)}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="policy-keyword" label="关键词">
                <Input
                  id="policy-keyword"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="规则编码 / 名称"
                />
              </FormField>
            </div>

            {loading ? (
              <p className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 加载中…
              </p>
            ) : policies.length === 0 ? (
              <EmptyState
                icon={<ShieldCheck className="size-6" />}
                title={filtered ? '没有命中的规则' : '还没有登记任何自动审批规则'}
                description={
                  filtered
                    ? '换个筛选条件再看看。'
                    : '没有生效规则时，单据一律走人工审批 —— 这是安全的一侧。'
                }
              />
            ) : (
              <div className="space-y-3">
                {policies.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {row.ruleName}
                      </span>
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {policyStatusLabel(row.status)}
                      </Badge>
                      <Badge tone="neutral">{row.ruleCode}</Badge>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
                      <span>单据类型 {row.documentTypeCode}</span>
                      <span>币种 {row.currencyCode}</span>
                      <span>
                        小额上限{' '}
                        {row.autoApproveMaxAmount === null
                          ? '未设（任何金额都不自动通过）'
                          : formatMoney(row.autoApproveMaxAmount, row.currencyCode)}
                      </span>
                      <span>
                        硬上限{' '}
                        {row.amountHardLimit === null
                          ? '未设'
                          : formatMoney(row.amountHardLimit, row.currencyCode)}
                      </span>
                      <span>
                        上游审批流 {row.upstreamFlowId ? row.upstreamFlowId.slice(0, 8) : '未配置'}
                      </span>
                    </div>
                    {row.status === 'ACTIVE' && (
                      <div className="mt-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={async () => {
                            setBusy(true);
                            try {
                              await disablePolicy(row.id);
                              setNotice('规则已停用（历史决策仍可复现）。');
                              await load();
                            } catch (e) {
                              setFormError(e instanceof Error ? e.message : '停用失败');
                            } finally {
                              setBusy(false);
                            }
                          }}
                        >
                          停用
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="登记规则" description="规则里没有生效的那条，单据就一律走人工。">
            <div className="space-y-3">
              <FormField id="rule-code" label="规则编码" required>
                <Input
                  id="rule-code"
                  value={policyForm.ruleCode}
                  onChange={(e) => setPolicyForm({ ...policyForm, ruleCode: e.target.value })}
                />
              </FormField>
              <FormField id="rule-name" label="规则名称" required>
                <Input
                  id="rule-name"
                  value={policyForm.ruleName}
                  onChange={(e) => setPolicyForm({ ...policyForm, ruleName: e.target.value })}
                />
              </FormField>
              <FormField id="rule-document-type" label="单据类型" required>
                <Input
                  id="rule-document-type"
                  value={policyForm.documentTypeCode}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, documentTypeCode: e.target.value })
                  }
                />
              </FormField>
              <FormField id="rule-currency" label="币种" required>
                <Input
                  id="rule-currency"
                  value={policyForm.currencyCode}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, currencyCode: e.target.value.toUpperCase() })
                  }
                />
              </FormField>
              <FormField
                id="rule-small-limit"
                label="小额上限"
                hint="超过它 ⇒ 走人工（金额大不是「明显不合规」，不该自动放行）"
              >
                <Input
                  id="rule-small-limit"
                  inputMode="decimal"
                  value={policyForm.autoApproveMaxAmount}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, autoApproveMaxAmount: e.target.value })
                  }
                />
              </FormField>
              <FormField
                id="rule-hard-limit"
                label="硬上限"
                hint="越过它 ⇒ 金额越界（确定性违规，可自动驳回）"
              >
                <Input
                  id="rule-hard-limit"
                  inputMode="decimal"
                  value={policyForm.amountHardLimit}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, amountHardLimit: e.target.value })
                  }
                />
              </FormField>
              <FormField
                id="rule-flow"
                label="上游审批流 id"
                hint="只读引用：人工审批时提交给哪条流程"
              >
                <Input
                  id="rule-flow"
                  value={policyForm.upstreamFlowId}
                  onChange={(e) => setPolicyForm({ ...policyForm, upstreamFlowId: e.target.value })}
                />
              </FormField>
              <FormField id="rule-status" label="状态">
                <select
                  id="rule-status"
                  className={selectClass}
                  value={policyForm.status}
                  onChange={(e) => setPolicyForm({ ...policyForm, status: e.target.value })}
                >
                  {POLICY_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {policyStatusLabel(s)}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="rule-priority" label="优先级">
                <Input
                  id="rule-priority"
                  inputMode="numeric"
                  value={policyForm.priority}
                  onChange={(e) => setPolicyForm({ ...policyForm, priority: e.target.value })}
                />
              </FormField>
              {formError && (
                <Notice tone="danger" title="还不能保存">
                  {formError}
                </Notice>
              )}
              <Button onClick={() => void submitPolicy()} disabled={busy}>
                登记规则
              </Button>
            </div>
          </SectionCard>
        </div>
      )}

      {tab === 'signals' && (
        <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
          <SectionCard
            title="风险信号码表"
            description="信号的性质决定「能不能自动驳回」：确定性违规可以，主观风险只走人工。"
            extra={<Badge tone="info">{signals.length} 条</Badge>}
          >
            {loading ? (
              <p className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 加载中…
              </p>
            ) : signals.length === 0 ? (
              <EmptyState
                icon={<FileWarning className="size-6" />}
                title="还没有登记风险信号码"
                description="没登记过的码按「主观风险」从严处理 —— 调用方编一个码就能驳单这件事不可能发生。"
              />
            ) : (
              <div className="space-y-3">
                {signals.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {row.signalName}
                      </span>
                      <Badge tone={BADGE_TONE[signalKindTone(row.signalKind)]}>
                        {signalKindLabel(row.signalKind)}
                      </Badge>
                      {row.status !== 'ACTIVE' && <Badge tone="neutral">已停用</Badge>}
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1.5">
                      编码 {row.signalCode}
                      {row.description ? ` · ${row.description}` : ''}
                    </div>
                    {row.status === 'ACTIVE' && (
                      <div className="mt-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={async () => {
                            setBusy(true);
                            try {
                              await disableSignal(row.id);
                              setNotice('信号码已停用（历史决策仍可复现）。');
                              await load();
                            } catch (e) {
                              setFormError(e instanceof Error ? e.message : '停用失败');
                            } finally {
                              setBusy(false);
                            }
                          }}
                        >
                          停用
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="登记信号码"
            description="「确定性违规」= 事实层面已经不合规，可以自动驳回。"
          >
            <div className="space-y-3">
              <FormField id="signal-code" label="信号编码" required>
                <Input
                  id="signal-code"
                  value={signalForm.signalCode}
                  onChange={(e) => setSignalForm({ ...signalForm, signalCode: e.target.value })}
                />
              </FormField>
              <FormField id="signal-name" label="信号名称" required>
                <Input
                  id="signal-name"
                  value={signalForm.signalName}
                  onChange={(e) => setSignalForm({ ...signalForm, signalName: e.target.value })}
                />
              </FormField>
              <FormField id="signal-kind" label="信号性质" required>
                <select
                  id="signal-kind"
                  className={selectClass}
                  value={signalForm.signalKind}
                  onChange={(e) => setSignalForm({ ...signalForm, signalKind: e.target.value })}
                >
                  {SIGNAL_KINDS.map((k) => (
                    <option key={k} value={k}>
                      {signalKindLabel(k)}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="signal-description" label="说明">
                <Input
                  id="signal-description"
                  value={signalForm.description}
                  onChange={(e) => setSignalForm({ ...signalForm, description: e.target.value })}
                />
              </FormField>
              {formError && (
                <Notice tone="danger" title="还不能保存">
                  {formError}
                </Notice>
              )}
              <Button onClick={() => void submitSignal()} disabled={busy}>
                登记信号码
              </Button>
            </div>
          </SectionCard>
        </div>
      )}

      {tab === 'decisions' && (
        <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
          <SectionCard
            title="自动审批决策台账"
            description="只追加：已写下的结论与依据不可修改。依据记的是**当时**的规则与信号快照。"
            extra={<Badge tone="info">{decisions.length} 条</Badge>}
          >
            <div className="mb-4 grid gap-3 sm:grid-cols-3">
              <FormField id="decision-filter" label="决策">
                <select
                  id="decision-filter"
                  className={selectClass}
                  value={decisionFilter}
                  onChange={(e) => setDecisionFilter(e.target.value)}
                >
                  <option value="">全部</option>
                  {DECISIONS.map((d) => (
                    <option key={d} value={d}>
                      {decisionLabel(d)}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="decision-keyword" label="关键词">
                <Input
                  id="decision-keyword"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="单据号 / 理由"
                />
              </FormField>
            </div>

            {loading ? (
              <p className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 加载中…
              </p>
            ) : decisions.length === 0 ? (
              <EmptyState
                icon={<ClipboardCheck className="size-6" />}
                title={filtered ? '没有命中的决策' : '还没有任何自动审批决策'}
                description={
                  filtered
                    ? '换个筛选条件再看看。'
                    : '业务系统提交单据做判定后，这里会出现逐条结论与依据。'
                }
              />
            ) : (
              <div className="space-y-3">
                {decisions.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {row.documentRef}
                      </span>
                      <Badge tone={BADGE_TONE[decisionTone(row.decision)]}>
                        {decisionLabel(row.decision)}
                      </Badge>
                      {row.executionState === 'SUBMIT_FAILED' && (
                        <Badge tone="danger">提交上游失败（仍需人工）</Badge>
                      )}
                      {row.executionState === 'SUBMITTED_TO_APPROVAL' && (
                        <Badge tone="info">已提交上游人工审批</Badge>
                      )}
                      <Badge tone="neutral">{row.reasonCode}</Badge>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
                      <span>金额 {formatMoney(row.amount, row.currencyCode)}</span>
                      <span>来源 {row.sourceSystem}</span>
                      <span>判定于 {formatDateTime(row.decidedAt)}</span>
                    </div>
                    <div className="mt-2">
                      <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                        查看决策依据
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <div className="space-y-4">
            <SectionCard title="发起一次判定" description="判定会写进台账（决策 + 依据快照）。">
              <div className="space-y-3">
                <FormField id="eval-document-type" label="单据类型" required>
                  <Input
                    id="eval-document-type"
                    value={evaluateForm.documentTypeCode}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, documentTypeCode: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="eval-document-ref" label="单据号" required>
                  <Input
                    id="eval-document-ref"
                    value={evaluateForm.documentRef}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, documentRef: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="eval-source" label="来源系统" required>
                  <Input
                    id="eval-source"
                    value={evaluateForm.sourceSystem}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, sourceSystem: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="eval-currency" label="币种">
                  <Input
                    id="eval-currency"
                    value={evaluateForm.currencyCode}
                    onChange={(e) =>
                      setEvaluateForm({
                        ...evaluateForm,
                        currencyCode: e.target.value.toUpperCase(),
                      })
                    }
                  />
                </FormField>
                <FormField id="eval-amount" label="金额">
                  <Input
                    id="eval-amount"
                    inputMode="decimal"
                    value={evaluateForm.amount}
                    onChange={(e) => setEvaluateForm({ ...evaluateForm, amount: e.target.value })}
                  />
                </FormField>
                <FormField id="eval-supplier" label="供应商 id">
                  <Input
                    id="eval-supplier"
                    value={evaluateForm.supplierId}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, supplierId: e.target.value })
                    }
                  />
                </FormField>
                <label className="text-label-13 text-foreground flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={evaluateForm.supplierVerified}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, supplierVerified: e.target.checked })
                    }
                  />
                  供应商已验证（这一条不可配置，缺了就不自动通过）
                </label>
                <FormField
                  id="eval-signals"
                  label="风险信号码"
                  hint="逗号分隔；未登记的码按主观风险从严处理"
                >
                  <Input
                    id="eval-signals"
                    value={evaluateForm.riskSignals}
                    onChange={(e) =>
                      setEvaluateForm({ ...evaluateForm, riskSignals: e.target.value })
                    }
                  />
                </FormField>
                {formError && (
                  <Notice tone="danger" title="还不能提交">
                    {formError}
                  </Notice>
                )}
                <Button onClick={() => void submitEvaluate()} disabled={busy}>
                  发起判定
                </Button>
              </div>
            </SectionCard>

            {detail && (
              <SectionCard title="决策依据" description="判定当时依据的规则与信号，逐条可查。">
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={BADGE_TONE[decisionTone(detail.decision)]}>
                      {decisionLabel(detail.decision)}
                    </Badge>
                    <Badge tone="neutral">{detail.reasonCode}</Badge>
                    <Badge tone="neutral">{detail.decisionNo}</Badge>
                  </div>
                  <p className="text-copy-13 text-foreground leading-relaxed">
                    {detail.reasonMessage}
                  </p>
                  <p className="text-copy-13 text-muted-foreground leading-relaxed">
                    {checkSummary(detail.checks)}
                  </p>

                  <div>
                    <div className="text-label-13 text-foreground mb-1.5">逐条判定</div>
                    <ul className="space-y-1.5">
                      {detail.checks.map((c) => (
                        <li key={c.code} className="flex flex-wrap items-center gap-2">
                          <Badge tone={c.passed ? 'success' : 'danger'}>
                            {c.passed ? '成立' : '不成立'}
                          </Badge>
                          <span className="text-copy-13 text-foreground">{c.code}</span>
                          <span className="text-copy-13 text-muted-foreground">{c.detail}</span>
                        </li>
                      ))}
                    </ul>
                  </div>

                  {detail.policy && (
                    <div className="text-copy-13 text-muted-foreground">
                      <div className="text-label-13 text-foreground mb-1.5">当时的规则快照</div>
                      {detail.policy.found ? (
                        <div>
                          {detail.policy.ruleName}（{detail.policy.ruleCode}）· 小额上限{' '}
                          {detail.policy.autoApproveMaxAmount === null
                            ? '未设'
                            : formatMoney(
                                detail.policy.autoApproveMaxAmount,
                                detail.policy.currencyCode,
                              )}{' '}
                          · 硬上限{' '}
                          {detail.policy.amountHardLimit === null
                            ? '未设'
                            : formatMoney(
                                detail.policy.amountHardLimit,
                                detail.policy.currencyCode,
                              )}
                        </div>
                      ) : (
                        <div>判定当时没有生效规则（⇒ 走人工）。</div>
                      )}
                    </div>
                  )}

                  {detail.riskSignals.length > 0 && (
                    <div>
                      <div className="text-label-13 text-foreground mb-1.5">当时收到的风险信号</div>
                      <ul className="space-y-1">
                        {detail.riskSignals.map((s) => (
                          <li key={s.code} className="flex flex-wrap items-center gap-2">
                            <Badge tone={BADGE_TONE[signalKindTone(s.kind)]}>
                              {signalKindLabel(s.kind)}
                            </Badge>
                            <span className="text-copy-13 text-foreground">{s.code}</span>
                            {!s.registered && (
                              <span className="text-copy-13 text-muted-foreground">
                                未登记 ⇒ 按主观风险从严
                              </span>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <div className="text-copy-13 text-muted-foreground">
                    <div className="text-label-13 text-foreground mb-1.5">金额分层路由</div>
                    {routingSummary(detail.routing)}
                  </div>
                </div>
              </SectionCard>
            )}
          </div>
        </div>
      )}

      {tab === 'escalations' && (
        <SectionCard
          title="超时自动升级"
          description="上游只定义超时规则、不实现定时器 —— 扫描与判定是本模块的活。执行时以发起人自己的身份在上游完成。"
          extra={
            <div className="flex items-center gap-2">
              <select
                className={selectClass}
                value={escalationStatus}
                onChange={(e) => setEscalationStatus(e.target.value)}
              >
                <option value="">全部状态</option>
                {ESCALATION_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
              <Button size="sm" variant="outline" onClick={() => void runScan()} disabled={busy}>
                <Timer className="size-4" /> 扫描超时
              </Button>
            </div>
          }
        >
          {notice && (
            <Notice tone="info" title="最近一次操作">
              {notice}
            </Notice>
          )}
          {loading ? (
            <p className="text-copy-13 text-muted-foreground flex items-center gap-2">
              <Loader2 className="size-4 animate-spin" /> 加载中…
            </p>
          ) : escalations.length === 0 ? (
            <EmptyState
              icon={<ListChecks className="size-6" />}
              title="没有超时单据"
              description="已提交上游、且压过时限的单据会出现在这里。"
            />
          ) : (
            <div className="space-y-3">
              {escalations.map((row) => (
                <div key={row.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      节点 {row.nodeKey}
                    </span>
                    <Badge tone="neutral">{escalationActionLabel(row.timeoutAction)}</Badge>
                    <Badge
                      tone={
                        row.status === 'EXECUTED'
                          ? 'success'
                          : row.status === 'FAILED'
                            ? 'danger'
                            : 'warning'
                      }
                    >
                      {row.status === 'DETECTED'
                        ? '已判定，待执行'
                        : row.status === 'EXECUTED'
                          ? '已执行'
                          : row.status === 'FAILED'
                            ? '执行失败'
                            : '已被后一条取代'}
                    </Badge>
                  </div>
                  <div className="text-copy-13 text-muted-foreground mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
                    <span>阈值 {row.timeoutHours} 小时</span>
                    <span>已等待 {row.elapsedHours} 小时</span>
                    <span>判定于 {formatDateTime(row.detectedAt)}</span>
                    <span>上游实例 {row.upstreamInstanceId.slice(0, 8)}</span>
                  </div>
                  {row.executionDetail && (
                    <p className="text-copy-13 text-foreground mt-1.5">{row.executionDetail}</p>
                  )}
                  {row.status === 'DETECTED' && (
                    <div className="mt-2">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void runExecute(row.id)}
                      >
                        执行升级
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'capabilities' && (
        <SectionCard title="能力声明" description="这条模块能做什么、不能做什么、边界归谁。">
          {capabilities ? (
            <div className="space-y-4">
              <Notice
                tone={capabilities.upstreamProgrammaticDecisionChannel ? 'info' : 'warning'}
                title="上游的程序化决策通道"
              >
                {capabilities.upstreamProgrammaticDecisionChannel
                  ? '上游存在程序化决策通道。'
                  : '上游**没有**程序化通过/驳回通道：本模块只做「判定 + 记录 + 经授权端口转交」，审批动作仍在上游由人按。'}
              </Notice>
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">模块与上游</div>
                  <p className="text-copy-13 text-muted-foreground">{capabilities.module}</p>
                  <p className="text-copy-13 text-muted-foreground">
                    上游：{capabilities.upstream}
                  </p>
                </div>
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">决策归属</div>
                  <p className="text-copy-13 text-muted-foreground">
                    {capabilities.decisionOwnership}
                  </p>
                </div>
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">结论取值</div>
                  <div className="flex flex-wrap gap-2">
                    {capabilities.decisions.map((d) => (
                      <Badge key={d} tone={BADGE_TONE[decisionTone(d)]}>
                        {decisionLabel(d)}
                      </Badge>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">判定项</div>
                  <div className="flex flex-wrap gap-2">
                    {capabilities.checks.map((c) => (
                      <Badge key={c} tone="neutral">
                        {c}
                      </Badge>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">权限码</div>
                  <div className="flex flex-wrap gap-2">
                    {capabilities.permissions.map((p) => (
                      <Badge key={p} tone="neutral">
                        {p}
                      </Badge>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="text-label-13 text-foreground mb-1.5">上游建单通道</div>
                  <p className="text-copy-13 text-muted-foreground">
                    {capabilities.upstreamIntakeChannel}
                  </p>
                </div>
              </div>
            </div>
          ) : (
            <EmptyState icon={<CircleAlert className="size-6" />} title="能力声明读不到" />
          )}
        </SectionCard>
      )}
    </div>
  );
}
