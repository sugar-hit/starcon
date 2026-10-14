'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState } from '@starcon/ui';
import { AlertTriangle, FlaskConical, Plus, RefreshCw, Trash2 } from 'lucide-react';
import type {
  ApprovalDocumentTypeView,
  ApprovalEffectivePolicyView,
  ApprovalEvaluationView,
  ApprovalFlowView,
  ApprovalRuleSetDetailView,
  ApprovalRuleSetView,
  ApprovalRuleValidationView,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '@/lib/api';
import { Field, Editor, control } from '../common/fields';
import { useIdentity } from './identity-shell';
import {
  DECISION_LABELS,
  DECISION_TONES,
  DEFAULT_ACTION_LABELS,
  DIMENSION_HINTS,
  DIMENSION_LABELS,
  DIMENSION_MODES,
  GUARD_DIMENSIONS,
  RULE_SET_STATUS_LABELS,
  RULE_SET_STATUS_TONES,
  SEVERITY_LABELS,
  SEVERITY_TONES,
  type RuleDraft,
  type RuleSetDraft,
  activeExemptionCeilings,
  conditionSummary,
  emptyDraft,
  emptyRule,
  ruleSetSummary,
  toRequestBody,
  validateDraft,
} from './approval-rule-model';

/**
 * 17.4.1.2 审批规则。
 *
 * 四个区块对应蓝图点名的四件事：**触发条件规则**（金额区间、跨法人、跨部门、新供应商、
 * 高风险商品）、**免审规则**（小额免审）、**生效策略**（一条都没命中时怎么办），
 * 以及**规则集**本身（有序规则，先命中先生效）。
 *
 * <p>这个页面配的是「这次提交会不会触发审批」，**不产生审批实例**：没有待办、没有计时器、
 * 不会因为配了免审就真的替你把单子放过去。页面里的「试算」只对已保存的规则做一次只读判定，
 * 结论与运行时一致，但不会建任何审批单（那是 17.4.2.1 的运行时）。
 *
 * <p>页面把蓝图那句警告摆在最显眼的位置：**免审必须有上限金额**。没有上限的免审等于取消管控，
 * 前后端都会拒绝；免审也不允许豁免跨法人 / 跨部门 / 新供应商 / 高风险商品 —— 那是绕过大额管控
 * 的第二种走法，光卡金额不够。
 */
export function ApprovalRuleWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [sets, setSets] = useState<ApprovalRuleSetView[]>([]);
  const [details, setDetails] = useState<Record<string, ApprovalRuleSetDetailView>>({});
  const [reports, setReports] = useState<Record<string, ApprovalRuleValidationView>>({});
  const [documentTypes, setDocumentTypes] = useState<ApprovalDocumentTypeView[]>([]);
  const [flows, setFlows] = useState<ApprovalFlowView[]>([]);
  const [policies, setPolicies] = useState<Record<string, ApprovalEffectivePolicyView>>({});

  const [editing, setEditing] = useState<{ id: string | null; draft: RuleSetDraft } | null>(null);
  const [probe, setProbe] = useState({
    documentTypeCode: '',
    currencyCode: 'USD',
    amount: '',
    crossLegalEntity: false,
    crossDepartment: false,
    newVendor: false,
    highRiskProduct: false,
  });
  const [evaluation, setEvaluation] = useState<ApprovalEvaluationView | null>(null);
  const [probing, setProbing] = useState(false);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.approval_rule.read');
  const writable = can('identity.approval_rule.create');

  const load = useCallback(async () => {
    setError('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有任何规则」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const [setList, typeList] = await Promise.all([
        api<ApprovalRuleSetView[]>('identity/approval-rules'),
        api<ApprovalDocumentTypeView[]>('identity/approval-flows/document-types'),
      ]);
      setSets(setList);
      setDocumentTypes(typeList);
      // 送审规则必须指向一条**生效**流程，所以编辑器的候选只给生效流程。
      // 读不到就让用户手填 id，而不是假装有下拉。
      api<ApprovalFlowView[]>('identity/approval-flows?status=ACTIVE')
        .then(setFlows)
        .catch(() => setFlows([]));
      const detailsMap: Record<string, ApprovalRuleSetDetailView> = {};
      for (const s of setList) {
        try {
          detailsMap[s.id] = await api<ApprovalRuleSetDetailView>(
            `identity/approval-rules/${s.id}`,
          );
        } catch {
          // 单条读失败不该让整页不可用：这一条先缺着，其余照常显示。
        }
      }
      setDetails(detailsMap);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '审批规则加载失败，请稍后重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadPolicy = useCallback(async (documentTypeCode: string) => {
    if (!documentTypeCode) return;
    try {
      const policy = await api<ApprovalEffectivePolicyView>(
        `identity/approval-rules/effective-policy?documentTypeCode=${encodeURIComponent(documentTypeCode)}`,
      );
      setPolicies((prev) => ({ ...prev, [documentTypeCode]: policy }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '生效策略读取失败');
    }
  }, []);

  const openReport = useCallback(async (id: string) => {
    try {
      const report = await api<ApprovalRuleValidationView>(
        `identity/approval-rules/${id}/validation`,
      );
      setReports((prev) => ({ ...prev, [id]: report }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '体检失败');
    }
  }, []);

  const save = useCallback(async () => {
    if (!editing) return;
    const errors = validateDraft(
      editing.draft,
      flows.map((f) => f.id),
    );
    if (errors.length > 0) {
      setError(errors.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const body = toRequestBody(editing.draft);
      if (editing.id) await api(`identity/approval-rules/${editing.id}`, 'PUT', body);
      else await api('identity/approval-rules', 'POST', body);
      setNotice(editing.id ? '规则集已更新' : '规则集已创建');
      setEditing(null);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '保存失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, [editing, flows, load]);

  const remove = useCallback(
    async (id: string) => {
      setBusy(true);
      setError('');
      try {
        await api(`identity/approval-rules/${id}`, 'DELETE');
        setNotice('规则集已删除；该单据类型回到「未配置生效规则」默认放行');
        await load();
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '删除失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const runProbe = useCallback(async () => {
    if (!probe.documentTypeCode) {
      setError('试算前请选择单据类型');
      return;
    }
    setProbing(true);
    setError('');
    try {
      const body: Record<string, unknown> = {
        documentTypeCode: probe.documentTypeCode,
        crossLegalEntity: probe.crossLegalEntity,
        crossDepartment: probe.crossDepartment,
        newVendor: probe.newVendor,
        highRiskProduct: probe.highRiskProduct,
      };
      if (probe.currencyCode.trim()) body.currencyCode = probe.currencyCode.trim().toUpperCase();
      if (probe.amount.trim()) body.amount = probe.amount.trim();
      const result = await api<ApprovalEvaluationView>(
        'identity/approval-rules/evaluate',
        'POST',
        body,
      );
      setEvaluation(result);
    } catch (e) {
      setEvaluation(null);
      setError(e instanceof ApiError ? e.message : '试算失败');
    } finally {
      setProbing(false);
    }
  }, [probe]);

  const activeCount = useMemo(() => sets.filter((s) => s.status === 'ACTIVE').length, [sets]);
  const controlled = useMemo(
    () => Object.values(policies).filter((p) => p.controlled).length,
    [policies],
  );
  const activeFlows = useMemo(() => flows.filter((f) => f.status === 'ACTIVE'), [flows]);

  if (!ready) return <p className="text-muted-foreground text-sm">正在加载审批规则…</p>;
  if (!readable)
    return <p className="text-muted-foreground text-sm">当前角色没有审批规则的查看权限。</p>;

  const draft = editing?.draft;

  return (
    <div className="space-y-5">
      <section className="bg-card space-y-3 rounded-lg border p-6">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold">审批规则</h2>
          <Badge tone="success">{activeCount} 套生效中</Badge>
          <Badge>{sets.length} 套规则集</Badge>
          {controlled > 0 && <Badge tone="primary">{controlled} 个单据类型受管控</Badge>}
        </div>
        <p className="text-muted-foreground text-sm">
          按金额区间、跨法人、跨部门、新供应商、高风险商品配置触发条件；规则<b>先命中先生效</b>，
          一条都没命中时按规则集的生效策略处理。
        </p>
        <p className="text-muted-foreground text-sm">
          <b>免审必须设上限金额</b> —— 没有上限的免审会被用来绕过大额管控，前后端都拒。
          免审也不允许豁免跨法人 / 跨部门 / 新供应商 / 高风险商品。
        </p>
        {notice && (
          <p role="status" className="text-primary text-sm">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="text-destructive text-sm">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button
            onClick={() => void load()}
            variant="outline"
            size="sm"
            disabled={busy}
            aria-label="刷新"
          >
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
          {writable && (
            <Button size="sm" onClick={() => setEditing({ id: null, draft: emptyDraft() })}>
              <Plus className="h-4 w-4" /> 新建规则集
            </Button>
          )}
        </div>
      </section>

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <h3 className="font-semibold">生效策略</h3>
        <p className="text-muted-foreground text-sm">
          选一个单据类型看它现在受不受管控、有哪些免审额度。没有任何管控时按蓝图「未注册的变更类操作
          默认可直接执行」放行。
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-64 flex-1">
            <Field label="单据类型">
              <select
                className={control}
                value={probe.documentTypeCode}
                onChange={(e) => {
                  setProbe((p) => ({ ...p, documentTypeCode: e.target.value }));
                  setEvaluation(null);
                  void loadPolicy(e.target.value);
                }}
              >
                <option value="">请选择单据类型</option>
                {documentTypes.map((t) => (
                  <option key={t.code} value={t.code}>
                    {t.name}（{t.code}）
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </div>
        {probe.documentTypeCode && !policies[probe.documentTypeCode] && (
          <p className="text-muted-foreground text-sm">正在读取生效策略…</p>
        )}
        {policies[probe.documentTypeCode] && (
          <EffectivePolicyPanel policy={policies[probe.documentTypeCode]!} />
        )}
      </section>

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <h3 className="flex items-center gap-2 font-semibold">
          <FlaskConical className="h-4 w-4" /> 试算
        </h3>
        <p className="text-muted-foreground text-sm">
          对<b>已保存</b>的规则做一次只读判定，结论与真正提交时一致。这里不建审批单、不出待办 ——
          运行时是 17.4.2.1 的职责。
        </p>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="币种">
            <input
              className={control}
              value={probe.currencyCode}
              placeholder="USD"
              onChange={(e) => setProbe((p) => ({ ...p, currencyCode: e.target.value }))}
            />
          </Field>
          <Field label="金额" hint="留空表示这次提交不带金额">
            <input
              className={control}
              value={probe.amount}
              placeholder="8000"
              onChange={(e) => setProbe((p) => ({ ...p, amount: e.target.value }))}
            />
          </Field>
          {GUARD_DIMENSIONS.map((g) => (
            <label key={g.field} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={probe[g.field]}
                onChange={(e) => setProbe((p) => ({ ...p, [g.field]: e.target.checked }))}
              />
              {g.label}
            </label>
          ))}
        </div>
        <Button
          size="sm"
          variant="outline"
          disabled={probing || !probe.documentTypeCode}
          onClick={() => void runProbe()}
        >
          {probing ? '试算中…' : '试算这次提交'}
        </Button>
        {evaluation && <EvaluationPanel result={evaluation} />}
      </section>

      <section className="space-y-3">
        {sets.length === 0 ? (
          <EmptyState
            title="还没有配置任何审批规则"
            description="没有规则的单据类型按默认放行处理。需要管控时先在审批流定义里注册生效流程，再来这里配触发条件。"
          />
        ) : (
          sets.map((s) => {
            const detail = details[s.id];
            const rules = detail?.rules ?? [];
            const report = reports[s.id];
            return (
              <article key={s.id} className="bg-card space-y-3 rounded-lg border p-6">
                <div className="flex flex-wrap items-center gap-3">
                  <h4 className="font-semibold">{s.ruleSetName}</h4>
                  <Badge tone={RULE_SET_STATUS_TONES[s.status]}>
                    {RULE_SET_STATUS_LABELS[s.status]}
                  </Badge>
                  <Badge tone={DECISION_TONES[s.defaultAction]}>
                    {DEFAULT_ACTION_LABELS[s.defaultAction] ?? s.defaultAction}
                  </Badge>
                </div>
                <p className="text-muted-foreground text-sm">
                  {ruleSetSummary(s)} · 生效策略：
                  {DEFAULT_ACTION_LABELS[s.defaultAction] ?? s.defaultAction}
                </p>
                {!detail && <p className="text-muted-foreground text-sm">正在读取规则…</p>}
                {detail && rules.length === 0 && (
                  <p className="text-muted-foreground text-sm">该规则集当前没有规则。</p>
                )}
                {detail && (
                  <div className="space-y-2">
                    {rules.map((r) => (
                      <div
                        key={r.id}
                        className="border-border flex flex-wrap items-center gap-2 border-b pb-2 text-sm last:border-0"
                      >
                        <span className="text-muted-foreground">#{r.priority}</span>
                        <span className="font-medium">{r.ruleName}</span>
                        <Badge tone={DECISION_TONES[r.decision]}>
                          {DECISION_LABELS[r.decision] ?? r.decision}
                        </Badge>
                        {r.status !== 'ACTIVE' && <Badge tone="warning">已停用</Badge>}
                        <span className="text-muted-foreground">{conditionSummary(r)}</span>
                        {r.decision === 'SUBMIT_FOR_APPROVAL' && (
                          <span className="text-muted-foreground">
                            → {r.targetFlowName ?? r.targetFlowId}
                            {!r.targetFlowActive && (
                              <span className="text-destructive">（该流程已非生效）</span>
                            )}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
                {rules.some((r) => r.decision === 'EXEMPT') && (
                  <p className="text-muted-foreground text-sm">
                    免审额度（上限即封顶，达到即走下一条规则）：
                    {activeExemptionCeilings(rules)
                      .map((r) => `${r.amountCurrencyCode} ${r.amountMaxAmount}`)
                      .join(' · ') || '（当前没有生效中的免审额度）'}
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  {writable && (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() =>
                          setEditing({
                            id: s.id,
                            draft: {
                              documentTypeCode: s.documentTypeCode,
                              ruleSetName: s.ruleSetName,
                              status: s.status,
                              defaultAction: s.defaultAction,
                              description: s.description,
                              rules: rules.map(toDraft),
                            },
                          })
                        }
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void remove(s.id)}
                        aria-label={`删除规则集 ${s.ruleSetName}`}
                      >
                        <Trash2 className="h-4 w-4" /> 删除
                      </Button>
                    </>
                  )}
                  <Button size="sm" variant="outline" onClick={() => void openReport(s.id)}>
                    <AlertTriangle className="h-4 w-4" /> 结构体检
                  </Button>
                </div>
                {report && (
                  <ul className="space-y-1 text-sm">
                    {report.findings.map((f, i) => (
                      <li key={`${f.code}-${i}`}>
                        <Badge tone={SEVERITY_TONES[f.severity]}>
                          {SEVERITY_LABELS[f.severity]}
                        </Badge>{' '}
                        {f.message}
                      </li>
                    ))}
                    {report.findings.length === 0 && (
                      <li className="text-muted-foreground">没有发现问题。</li>
                    )}
                  </ul>
                )}
              </article>
            );
          })
        )}
      </section>

      {editing && draft && (
        <Editor
          title={editing.id ? '编辑审批规则集' : '新建审批规则集'}
          description="规则按优先级从小到大判定，先命中先生效。免审必须有上限金额，且不得豁免跨法人 / 跨部门 / 新供应商 / 高风险商品。"
          close={() => setEditing(null)}
        >
          <div className="max-h-[70vh] space-y-4 overflow-y-auto px-1">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="适用单据类型" hint="建后不可改：换单据类型等于换一套规则">
                <select
                  className={control}
                  value={draft.documentTypeCode}
                  disabled={Boolean(editing.id)}
                  onChange={(e) =>
                    setEditing((prev) =>
                      prev
                        ? {
                            ...prev,
                            draft: {
                              ...prev.draft,
                              documentTypeCode: e.target.value,
                              // 换单据类型就换一批可选流程：原来那条流程属于别的单据类型。
                              rules: prev.draft.rules.map((r) => ({ ...r, targetFlowId: null })),
                            },
                          }
                        : prev,
                    )
                  }
                >
                  <option value="">请选择</option>
                  {documentTypes.map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.name}（{t.code}）
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="规则集名称">
                <input
                  className={control}
                  value={draft.ruleSetName}
                  onChange={(e) =>
                    setEditing((prev) =>
                      prev
                        ? { ...prev, draft: { ...prev.draft, ruleSetName: e.target.value } }
                        : prev,
                    )
                  }
                />
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={draft.status}
                  onChange={(e) =>
                    setEditing((prev) =>
                      prev ? { ...prev, draft: { ...prev.draft, status: e.target.value } } : prev,
                    )
                  }
                >
                  {Object.entries(RULE_SET_STATUS_LABELS).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </Field>
              <Field
                label="生效策略（一条都没命中时）"
                hint="存在送审规则时只能是送审，否则没命中规则的提交会被静默放行"
              >
                <select
                  className={control}
                  value={draft.defaultAction}
                  onChange={(e) =>
                    setEditing((prev) =>
                      prev
                        ? { ...prev, draft: { ...prev.draft, defaultAction: e.target.value } }
                        : prev,
                    )
                  }
                >
                  {Object.entries(DEFAULT_ACTION_LABELS).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <div className="space-y-3">
              {draft.rules.map((rule, index) => (
                <RuleEditor
                  key={index}
                  rule={rule}
                  flows={activeFlows}
                  onChange={(next) =>
                    setEditing((prev) =>
                      prev
                        ? {
                            ...prev,
                            draft: {
                              ...prev.draft,
                              rules: prev.draft.rules.map((r, i) => (i === index ? next : r)),
                            },
                          }
                        : prev,
                    )
                  }
                  onRemove={
                    draft.rules.length > 1
                      ? () =>
                          setEditing((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  draft: {
                                    ...prev.draft,
                                    rules: prev.draft.rules.filter((_, i) => i !== index),
                                  },
                                }
                              : prev,
                          )
                      : undefined
                  }
                />
              ))}
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setEditing((prev) =>
                    prev
                      ? {
                          ...prev,
                          draft: {
                            ...prev.draft,
                            rules: [
                              ...prev.draft.rules,
                              emptyRule(
                                activeFlows.find(
                                  (f) => f.documentTypeCode === prev.draft.documentTypeCode,
                                )?.id ?? null,
                              ),
                            ],
                          },
                        }
                      : prev,
                  )
                }
              >
                <Plus className="h-4 w-4" /> 添加规则
              </Button>
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void save()}>
                保存
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}

function toDraft(r: {
  ruleName: string;
  priority: number;
  amountCurrencyCode: string | null;
  amountMinAmount: number | null;
  amountMaxAmount: number | null;
  crossLegalEntity: string;
  crossDepartment: string;
  newVendor: string;
  highRiskProduct: string;
  decision: string;
  targetFlowId: string | null;
  status: string;
  description: string;
}): RuleDraft {
  return {
    ruleName: r.ruleName,
    priority: r.priority,
    amountCurrencyCode: r.amountCurrencyCode ?? '',
    amountMinAmount: r.amountMinAmount === null ? '' : String(r.amountMinAmount),
    amountMaxAmount: r.amountMaxAmount === null ? '' : String(r.amountMaxAmount),
    crossLegalEntity: r.crossLegalEntity as RuleDraft['crossLegalEntity'],
    crossDepartment: r.crossDepartment as RuleDraft['crossDepartment'],
    newVendor: r.newVendor as RuleDraft['newVendor'],
    highRiskProduct: r.highRiskProduct as RuleDraft['highRiskProduct'],
    decision: r.decision,
    targetFlowId: r.targetFlowId,
    status: r.status,
    description: r.description,
  };
}

function RuleEditor({
  rule,
  flows,
  onChange,
  onRemove,
}: {
  rule: RuleDraft;
  flows: ApprovalFlowView[];
  onChange: (next: RuleDraft) => void;
  onRemove?: (() => void) | undefined;
}) {
  const set = <K extends keyof RuleDraft>(key: K, value: RuleDraft[K]) =>
    onChange({ ...rule, [key]: value });
  const candidateFlows = flows.filter((f) => f.status === 'ACTIVE' || f.id === rule.targetFlowId);
  return (
    <div className="border-border space-y-3 rounded-md border p-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="规则名称">
          <input
            className={control}
            value={rule.ruleName}
            onChange={(e) => set('ruleName', e.target.value)}
          />
        </Field>
        <Field label="优先级" hint="越小越先判定，同序会被拒">
          <input
            className={control}
            type="number"
            min={0}
            value={rule.priority}
            onChange={(e) => set('priority', Number(e.target.value))}
          />
        </Field>
        <Field label="命中后">
          <select
            className={control}
            value={rule.decision}
            onChange={(e) => set('decision', e.target.value)}
          >
            {Object.entries(DECISION_LABELS).map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="审批流"
          hint={rule.decision === 'EXEMPT' ? '免审不走流程' : '必须是本单据类型的生效流程'}
        >
          <select
            className={control}
            disabled={rule.decision === 'EXEMPT'}
            value={rule.targetFlowId ?? ''}
            onChange={(e) => set('targetFlowId', e.target.value || null)}
          >
            <option value="">{rule.decision === 'EXEMPT' ? '免审（不指定）' : '请选择'}</option>
            {candidateFlows.map((f) => (
              <option key={f.id} value={f.id}>
                {f.documentTypeName} · {f.flowName}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="币种" hint="留空表示不约束金额">
          <input
            className={control}
            placeholder="USD"
            value={rule.amountCurrencyCode}
            onChange={(e) => set('amountCurrencyCode', e.target.value.toUpperCase())}
          />
        </Field>
        <Field label="金额下限（含）">
          <input
            className={control}
            value={rule.amountMinAmount}
            onChange={(e) => set('amountMinAmount', e.target.value)}
          />
        </Field>
        <Field
          label="金额上限（不含）"
          hint={rule.decision === 'EXEMPT' ? '免审必填：没有上限等于取消管控' : '留空表示无上界'}
        >
          <input
            className={control}
            value={rule.amountMaxAmount}
            onChange={(e) => set('amountMaxAmount', e.target.value)}
          />
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {GUARD_DIMENSIONS.map((g) => (
          <Field key={g.field} label={g.label} hint={DIMENSION_HINTS[rule[g.field]] ?? ''}>
            <select
              className={control}
              value={rule[g.field]}
              onChange={(e) => set(g.field, e.target.value as RuleDraft[typeof g.field])}
            >
              {DIMENSION_MODES.map((m) => (
                <option key={m} value={m} disabled={m === 'REQUIRED' && rule.decision === 'EXEMPT'}>
                  {DIMENSION_LABELS[m]}
                </option>
              ))}
            </select>
          </Field>
        ))}
      </div>
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground text-sm">{conditionSummary(rule)}</span>
        {onRemove && (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto"
            onClick={onRemove}
            aria-label="删除该规则"
          >
            <Trash2 className="h-4 w-4" /> 移除
          </Button>
        )}
      </div>
    </div>
  );
}

function EffectivePolicyPanel({ policy }: { policy: ApprovalEffectivePolicyView }) {
  if (!policy.controlled)
    return (
      <p className="text-muted-foreground text-sm">
        当前没有为「{policy.documentTypeName}」配置生效规则集，提交按默认放行处理。
      </p>
    );
  return (
    <div className="space-y-2 text-sm">
      <p>
        受管控 · 生效规则集「{policy.activeRuleSetName}」·{' '}
        {DEFAULT_ACTION_LABELS[policy.defaultAction ?? ''] ?? policy.defaultAction}
        {policy.activeFlowName
          ? ` · 送审批流程「${policy.activeFlowName}」`
          : ' · 尚无生效审批流，送审无处可送'}
      </p>
      {policy.exemptionCeilings.length === 0 ? (
        <p className="text-muted-foreground">当前没有生效中的免审额度。</p>
      ) : (
        <ul className="space-y-1">
          {policy.exemptionCeilings.map((c) => (
            <li key={c.ruleId}>
              <Badge tone="success">免审</Badge> {c.ruleName} · {c.currencyCode} {c.maxAmount} 以内
              ·{' '}
              {conditionSummary({
                ...c,
                amountCurrencyCode: null,
                amountMinAmount: null,
                amountMaxAmount: null,
              })}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function EvaluationPanel({ result }: { result: ApprovalEvaluationView }) {
  return (
    <div className="border-border space-y-1 rounded-md border p-3 text-sm">
      <p>
        <Badge tone={DECISION_TONES[result.decision]}>
          {DECISION_LABELS[result.decision] ?? result.decision}
        </Badge>{' '}
        {result.reason}
      </p>
      <p className="text-muted-foreground">
        判定来源：
        {result.source === 'RULE'
          ? `命中规则「${result.matchedRuleName}」（优先级 #${result.matchedPriority}）`
          : result.source === 'DEFAULT'
            ? '没有规则命中，走生效策略'
            : '该单据类型未配置生效规则，默认放行'}
      </p>
      {result.exemptionCeilingAmount !== null && (
        <p className="text-muted-foreground">
          本次适用的免审上限：{result.exemptionCeilingCurrency} {result.exemptionCeilingAmount}
        </p>
      )}
    </div>
  );
}
