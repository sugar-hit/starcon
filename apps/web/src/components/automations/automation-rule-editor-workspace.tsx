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
} from '@starcon/ui';
import { FlaskConical, Loader2, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import {
  actionSpec,
  buildRuleInput,
  buildTestInput,
  describeCondition,
  emptyAction,
  emptyCondition,
  emptyDraft,
  fetchActionCatalog,
  fetchApiCatalog,
  fetchCapabilities,
  fetchEventSchemas,
  fetchTestRuns,
  fieldByPath,
  parseSamplePayload,
  runSampleTest,
  saveRule,
  summarizeTestResult,
  validateDraft,
  type ActionDraft,
  type ConditionDraft,
  type EditorDraft,
  type Tone,
} from './automation-rule-editor-model';
import type {
  AutomationRuleEditorActionCatalog,
  AutomationRuleEditorApiCatalog,
  AutomationRuleEditorCapabilities,
  AutomationRuleEditorEventSchema,
  AutomationRuleEditorTestResult,
  AutomationRuleEditorTestRunRow,
} from '../../../../../packages/api-contracts/src/index';

const selectClass =
  'border-border bg-background text-foreground h-9 w-full rounded-md border px-2 text-sm';

/** 模型的语气 → Badge 的语气（徽章只有 7 种 tone，这里用其中 4 种）。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

/**
 * 18.3.1.2「规则编辑器」工作区。
 *
 * 🔴 页面上三件事**不许**做错：
 *
 * 1. **「保存」写的是 18.3.1.1 的档案**（`POST /save` 委托登记入口）——
 *    本模块不另建规则表，条件 / 动作 / 启用范围三张表的写入所有者始终是 18.3.1.1。
 * 2. **「样本测试」不执行任何动作**。18.3.2 执行引擎尚未交付 ⇒ 测试只求值、
 *    只给动作计划。结果区**永远**显示「没有产生任何副作用」。
 * 3. **字段与参数合法性由后端目录判**。事件字段目录取不到时，页面写明「未校验」，
 *    不自己猜一套枚举 —— 猜出来的枚举会与后端漂移。
 */
export function AutomationRuleEditorWorkspace() {
  const [capabilities, setCapabilities] = useState<AutomationRuleEditorCapabilities | null>(null);
  const [eventSchemas, setEventSchemas] = useState<AutomationRuleEditorEventSchema[]>([]);
  const [actionCatalog, setActionCatalog] = useState<AutomationRuleEditorActionCatalog | null>(
    null,
  );
  const [apiCatalog, setApiCatalog] = useState<AutomationRuleEditorApiCatalog | null>(null);
  const [testRuns, setTestRuns] = useState<AutomationRuleEditorTestRunRow[]>([]);

  const [draft, setDraft] = useState<EditorDraft>(() => ({
    ...emptyDraft(),
  }));
  const [payloadText, setPayloadText] = useState('');
  const [testResult, setTestResult] = useState<AutomationRuleEditorTestResult | null>(null);

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [actionError, setActionError] = useState('');
  const [savedHint, setSavedHint] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    // 目录是四份独立的接口：一份读不到**不许**把整页打成空白 ——
    // 缺哪份就缺哪份，页面照常能用（缺的那部分标「未校验」）。
    const [caps, schemas, actions, apis] = await Promise.allSettled([
      fetchCapabilities(),
      fetchEventSchemas(),
      fetchActionCatalog(),
      fetchApiCatalog(),
    ]);
    if (caps.status === 'fulfilled') setCapabilities(caps.value);
    if (schemas.status === 'fulfilled') setEventSchemas(schemas.value);
    if (actions.status === 'fulfilled') setActionCatalog(actions.value);
    if (apis.status === 'fulfilled') setApiCatalog(apis.value);
    const failed = [
      caps.status === 'rejected' ? '能力声明' : null,
      schemas.status === 'rejected' ? '事件字段目录' : null,
      actions.status === 'rejected' ? '动作目录' : null,
      apis.status === 'rejected' ? '接口目录' : null,
    ].filter((x): x is string => x !== null);
    if (failed.length > 0) {
      setLoadError(`以下目录读不到，相关校验**未执行**：${failed.join('、')}`);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const currentSchema = useMemo(
    () =>
      eventSchemas.find(
        (s) =>
          s.eventType === draft.triggerEventType &&
          (draft.triggerSchemaVersion === null || s.schemaVersion === draft.triggerSchemaVersion),
      ) ?? null,
    [eventSchemas, draft.triggerEventType, draft.triggerSchemaVersion],
  );

  const problems = useMemo(
    () => validateDraft(draft, { schema: currentSchema, actionCatalog, apiCatalog }),
    [draft, currentSchema, actionCatalog, apiCatalog],
  );

  const patchDraft = useCallback((patch: Partial<EditorDraft>) => {
    setDraft((prev) => ({ ...prev, ...patch }));
    setSavedHint('');
    setActionError('');
  }, []);

  const onEventChange = useCallback(
    (eventType: string) => {
      const schema = eventSchemas.find((s) => s.eventType === eventType) ?? null;
      setDraft((prev) => ({
        ...prev,
        triggerEventType: eventType,
        triggerSchemaVersion: schema?.schemaVersion ?? null,
        // 换事件 ⇒ 旧条件的字段属于**上一个事件**的 schema，留着就是假字段。
        conditions: prev.conditions.map((c) => ({
          ...c,
          fieldPath: '',
          operator: '',
          valueText: '',
          valueNumber: null,
          valueBool: null,
        })),
      }));
      setSavedHint('');
    },
    [eventSchemas],
  );

  const patchCondition = useCallback((index: number, patch: Partial<ConditionDraft>) => {
    setDraft((prev) => ({
      ...prev,
      conditions: prev.conditions.map((c, i) => (i === index ? { ...c, ...patch } : c)),
    }));
    setSavedHint('');
  }, []);

  const patchAction = useCallback((index: number, patch: Partial<ActionDraft>) => {
    setDraft((prev) => ({
      ...prev,
      actions: prev.actions.map((a, i) => (i === index ? { ...a, ...patch } : a)),
    }));
    setSavedHint('');
  }, []);

  const onFieldChange = useCallback(
    (index: number, path: string) => {
      const field = fieldByPath(currentSchema, path);
      patchCondition(index, {
        fieldPath: path,
        operator: field?.allowedOperators?.[0] ?? '',
        valueText: '',
        valueNumber: null,
        valueBool: null,
      });
    },
    [currentSchema, patchCondition],
  );

  const addCondition = useCallback(() => {
    setDraft((prev) => ({
      ...prev,
      conditions: [...prev.conditions, emptyCondition(prev.conditions.length + 1)],
    }));
  }, []);

  const removeCondition = useCallback((index: number) => {
    setDraft((prev) => ({
      ...prev,
      conditions: prev.conditions.filter((_, i) => i !== index),
    }));
  }, []);

  const addAction = useCallback(() => {
    setDraft((prev) => ({
      ...prev,
      actions: [...prev.actions, emptyAction(prev.actions.length + 1)],
    }));
  }, []);

  const removeAction = useCallback((index: number) => {
    setDraft((prev) => ({
      ...prev,
      actions: prev.actions.filter((_, i) => i !== index),
    }));
  }, []);

  const onRunTest = useCallback(async () => {
    setActionError('');
    setTestResult(null);
    const parsed = parseSamplePayload(payloadText);
    if (!parsed.ok) {
      setActionError(parsed.reason);
      return;
    }
    setTesting(true);
    try {
      const result = await runSampleTest(buildTestInput(draft, parsed.payload));
      setTestResult(result);
      const runs = await fetchTestRuns({ limit: 20 });
      setTestRuns(runs);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : String(error));
    } finally {
      setTesting(false);
    }
  }, [draft, payloadText]);

  const onSave = useCallback(async () => {
    setActionError('');
    setSavedHint('');
    if (problems.length > 0) {
      setActionError(`还有 ${problems.length} 处没配好，先按下面的提示改`);
      return;
    }
    setSaving(true);
    try {
      const detail = await saveRule(buildRuleInput(draft));
      setDraft((prev) => ({ ...prev, id: detail.rule.id }));
      setSavedHint(`已保存「${detail.rule.name}」—— 规则档案由 18.3.1.1 登记，本页只是编辑器`);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : String(error));
    } finally {
      setSaving(false);
    }
  }, [draft, problems]);

  const summary = testResult ? summarizeTestResult(testResult) : null;
  const engineGap = capabilities?.gaps?.find((g) => !g.available) ?? null;

  return (
    <div className="space-y-4">
      <PageHeader
        title="规则编辑器"
        description="选触发事件 → 配条件 → 编排动作 → 用样本测试 → 保存。保存委托 18.3.1.1 登记规则档案。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : undefined} aria-hidden />
            重新加载目录
          </Button>
        }
      />

      <Notice tone="warning" title="规则执行引擎（18.3.2）尚未交付">
        本页的「样本测试」**只求值条件并给出动作计划，不会执行任何动作**。页面上没有「执行规则」
        的按钮 —— 一个点了必然失败的按钮会让运营以为「点一下就能跑」。
      </Notice>

      {loadError ? (
        <Notice tone="warning" title="部分目录没读到">
          {loadError}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力与边界" description="哪些真交付、哪些还没交付、归谁。">
          <ul className="space-y-1 text-sm">
            {capabilities.capabilities.map((c) => (
              <li key={c.moduleId} className="flex items-start gap-2">
                <Badge tone={c.available ? 'success' : 'neutral'}>
                  {c.available ? '已交付' : '未交付'}
                </Badge>
                <span>
                  <strong>{c.name}</strong>
                  <span className="text-muted">（{c.moduleId}）</span>
                  {c.note ? <span className="text-muted"> · {c.note}</span> : null}
                  {!c.available && c.ownerModule ? (
                    <span className="text-muted"> · 归 {c.ownerModule}</span>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      <SectionCard
        title="触发事件"
        description="事件类型与 schema 版本决定下面的字段目录。字段来自事件注册表登记的 payload_schema，不是写死的清单。"
      >
        {eventSchemas.length === 0 ? (
          <EmptyState
            title="还没有可用的触发事件"
            description="事件注册表里还没有登记任何事件 —— 没有触发事件，规则永远不会跑。"
          />
        ) : (
          <div className="space-y-3">
            <FormField
              id="editor-event"
              label="触发事件"
              hint="同一个事件可能有多个 schema 版本，判定要对着某一个版本算。"
              required
            >
              <select
                id="editor-event"
                className={selectClass}
                value={draft.triggerEventType}
                onChange={(e) => onEventChange(e.target.value)}
              >
                <option value="">（还没选）</option>
                {eventSchemas.map((s) => (
                  <option key={`${s.eventType}@${s.schemaVersion}`} value={s.eventType}>
                    {s.eventType}（v{s.schemaVersion}）
                  </option>
                ))}
              </select>
            </FormField>
            {currentSchema ? (
              <p className="text-muted text-sm">
                当前 schema v{currentSchema.schemaVersion} · 来源 {currentSchema.sourceService} ·{' '}
                {currentSchema.fields.length} 个字段
                {currentSchema.schemaDeclared
                  ? ''
                  : ' · ⚠ 该事件没有登记 payload schema，字段目录不完整'}
              </p>
            ) : null}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="条件"
        description="每条条件 = 字段 + 操作符 + 值。字段与操作符的可选范围由上面的事件字段目录决定。"
        extra={
          <Button variant="secondary" size="sm" onClick={addCondition}>
            <Plus aria-hidden /> 加一条条件
          </Button>
        }
      >
        <div className="space-y-3">
          {draft.conditions.length === 0 ? (
            <EmptyState title="还没有条件" description="没有条件的规则等于「全部命中」。" />
          ) : null}
          {draft.conditions.map((condition, index) => {
            const field = fieldByPath(currentSchema, condition.fieldPath);
            const operators = field?.allowedOperators ?? [];
            return (
              <div key={index} className="border-border space-y-2 rounded-md border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">条件 {index + 1}</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => removeCondition(index)}
                    aria-label={`删除条件 ${index + 1}`}
                  >
                    <Trash2 aria-hidden />
                  </Button>
                </div>
                <div className="grid gap-2 md:grid-cols-3">
                  <FormField id={`editor-condition-field-${index}`} label="字段">
                    <select
                      id={`editor-condition-field-${index}`}
                      className={selectClass}
                      value={condition.fieldPath}
                      onChange={(e) => onFieldChange(index, e.target.value)}
                      disabled={!currentSchema}
                    >
                      <option value="">
                        {currentSchema ? '（还没选）' : '（事件字段目录没读到，未校验）'}
                      </option>
                      {(currentSchema?.fields ?? []).map((f) => (
                        <option key={f.path} value={f.path}>
                          {f.label || f.path}
                          {f.required ? ' *' : ''}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id={`editor-condition-operator-${index}`} label="操作符">
                    <select
                      id={`editor-condition-operator-${index}`}
                      className={selectClass}
                      value={condition.operator}
                      onChange={(e) => patchCondition(index, { operator: e.target.value })}
                      disabled={operators.length === 0}
                    >
                      <option value="">
                        {operators.length === 0 ? '（先选字段）' : '（还没选）'}
                      </option>
                      {operators.map((op) => (
                        <option key={op} value={op}>
                          {op}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id={`editor-condition-value-${index}`} label="值">
                    {field?.type === 'NUMBER' ? (
                      <Input
                        id={`editor-condition-value-${index}`}
                        type="number"
                        value={condition.valueNumber ?? ''}
                        onChange={(e) =>
                          patchCondition(index, {
                            valueNumber: e.target.value === '' ? null : Number(e.target.value),
                          })
                        }
                      />
                    ) : field?.type === 'BOOLEAN' ? (
                      <select
                        id={`editor-condition-value-${index}`}
                        className={selectClass}
                        value={condition.valueBool === null ? '' : String(condition.valueBool)}
                        onChange={(e) =>
                          patchCondition(index, {
                            valueBool: e.target.value === '' ? null : e.target.value === 'true',
                          })
                        }
                      >
                        <option value="">（还没选）</option>
                        <option value="true">是</option>
                        <option value="false">否</option>
                      </select>
                    ) : (
                      <Input
                        id={`editor-condition-value-${index}`}
                        value={condition.valueText}
                        onChange={(e) => patchCondition(index, { valueText: e.target.value })}
                      />
                    )}
                  </FormField>
                </div>
                {field && field.enumValues.length > 0 ? (
                  <p className="text-muted text-sm">可选取值：{field.enumValues.join('、')}</p>
                ) : null}
              </div>
            );
          })}
        </div>
      </SectionCard>

      <SectionCard
        title="动作"
        description="五类动作：调接口 / 改状态 / 建任务 / 发通知 / 生成单据。必填参数按动作目录校验。"
        extra={
          <Button variant="secondary" size="sm" onClick={addAction}>
            <Plus aria-hidden /> 加一条动作
          </Button>
        }
      >
        <div className="space-y-3">
          {draft.actions.length === 0 ? (
            <EmptyState title="还没有动作" description="只判条件不做事，命中了也没有结果。" />
          ) : null}
          {draft.actions.map((action, index) => {
            const spec = actionSpec(actionCatalog, action.actionType);
            const needsTarget = action.actionType === 'CALL_API';
            return (
              <div key={index} className="border-border space-y-2 rounded-md border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">动作 {index + 1}</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => removeAction(index)}
                    aria-label={`删除动作 ${index + 1}`}
                  >
                    <Trash2 aria-hidden />
                  </Button>
                </div>
                <div className="grid gap-2 md:grid-cols-2">
                  <FormField id={`editor-action-type-${index}`} label="动作类型">
                    <select
                      id={`editor-action-type-${index}`}
                      className={selectClass}
                      value={action.actionType}
                      onChange={(e) => patchAction(index, { actionType: e.target.value })}
                    >
                      <option value="">
                        {actionCatalog ? '（还没选）' : '（动作目录没读到）'}
                      </option>
                      {(actionCatalog?.actionTypes ?? []).map((t) => (
                        <option key={t.actionType} value={t.actionType}>
                          {t.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField
                    id={`editor-action-target-${index}`}
                    label={spec?.targetLabel ?? '目标'}
                    hint={
                      needsTarget
                        ? '「调接口」必须选接口目录里真实存在的接口。'
                        : '可以留空（如发通知可由收件人决定）。'
                    }
                  >
                    {needsTarget ? (
                      <select
                        id={`editor-action-target-${index}`}
                        className={selectClass}
                        value={action.targetRef}
                        onChange={(e) => patchAction(index, { targetRef: e.target.value })}
                      >
                        <option value="">
                          {apiCatalog?.available ? '（还没选）' : '（接口目录不可用，未校验）'}
                        </option>
                        {(apiCatalog?.endpoints ?? []).map((ep) => (
                          <option
                            key={`${ep.service}-${ep.method}-${ep.path}`}
                            value={`${ep.service} ${ep.method} ${ep.path}`}
                          >
                            {ep.service} {ep.method} {ep.path} · {ep.summary}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <Input
                        id={`editor-action-target-${index}`}
                        value={action.targetRef}
                        onChange={(e) => patchAction(index, { targetRef: e.target.value })}
                      />
                    )}
                  </FormField>
                </div>
                <FormField
                  id={`editor-action-params-${index}`}
                  label="参数（JSON 对象）"
                  hint={
                    spec && spec.params.length > 0
                      ? `参数规格：${spec.params
                          .map((p) => `${p.label}(${p.name})${p.required ? ' 必填' : ''}`)
                          .join('、')}`
                      : '参数必须是合法 JSON 对象。'
                  }
                >
                  <textarea
                    id={`editor-action-params-${index}`}
                    className="border-border bg-background text-foreground min-h-20 w-full rounded-md border px-2 py-1 font-mono text-sm"
                    value={action.params}
                    onChange={(e) => patchAction(index, { params: e.target.value })}
                  />
                </FormField>
              </div>
            );
          })}
        </div>
      </SectionCard>

      <SectionCard
        title="样本测试"
        description="用一份样本载荷真的求值条件，给出动作计划。测试只求值，不执行。"
        extra={
          <Button variant="brand" onClick={() => void onRunTest()} disabled={testing}>
            {testing ? (
              <Loader2 className="animate-spin" aria-hidden />
            ) : (
              <FlaskConical aria-hidden />
            )}
            跑一次样本测试
          </Button>
        }
      >
        <div className="space-y-3">
          <FormField
            id="editor-payload"
            label="样本载荷（JSON）"
            hint="写错会明确报错 —— 拿空载荷去测出来的「不匹配」会被误读成「规则不对」。"
          >
            <textarea
              id="editor-payload"
              className="border-border bg-background text-foreground min-h-32 w-full rounded-md border px-2 py-1 font-mono text-sm"
              value={payloadText}
              onChange={(e) => setPayloadText(e.target.value)}
              placeholder={'{"order":{"amount":120,"channel":"AMAZON"}}'}
            />
          </FormField>

          {summary ? (
            <div className="space-y-2">
              <Badge tone={BADGE_TONE[summary.tone]}>{summary.headline}</Badge>
              <ul className="text-muted space-y-1 text-sm">
                {summary.notes.map((note, i) => (
                  <li key={i}>{note}</li>
                ))}
              </ul>
              {testResult && testResult.conditionEvaluations.length > 0 ? (
                <div className="space-y-1">
                  <h4 className="text-sm font-medium">逐条条件求值</h4>
                  <ul className="text-muted space-y-1 text-sm">
                    {testResult.conditionEvaluations.map((c, i) => (
                      <li key={i}>
                        {c.fieldPath} {c.operator} → {c.matched ? '命中' : '未命中'}
                        {c.actualValue ? `（实际值 ${c.actualValue}）` : ''}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {testResult && testResult.actionPlan.length > 0 ? (
                <div className="space-y-1">
                  <h4 className="text-sm font-medium">动作计划（不会执行）</h4>
                  <ol className="text-muted space-y-1 text-sm">
                    {testResult.actionPlan.map((a) => (
                      <li key={a.seq}>
                        {a.seq}. {a.actionTypeLabel} · 目标 {a.targetRef || '（无）'} · 参数{' '}
                        {a.params}
                      </li>
                    ))}
                  </ol>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      </SectionCard>

      <SectionCard title="测试历史" description="样本测试的只追加台账。">
        {testRuns.length === 0 ? (
          <EmptyState title="还没有样本测试记录" description="跑一次样本测试，这里就会有记录。" />
        ) : (
          <ul className="space-y-2 text-sm">
            {testRuns.map((run) => (
              <li
                key={run.id}
                className="border-border flex flex-wrap items-center gap-2 border-b pb-2"
              >
                <Badge tone={run.matched ? 'success' : 'warning'}>
                  {run.matched ? '命中' : '未命中'}
                </Badge>
                <span className="font-medium">{run.ruleName || '（未命名草稿）'}</span>
                <span className="text-muted">
                  第 {run.runNo} 次 · {run.matchedConditions}/{run.totalConditions} 条条件 · 计划{' '}
                  {run.plannedActions} 个动作
                </span>
                <span className="text-muted">{run.createdAt}</span>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      <SectionCard
        title="保存"
        description="保存委托 18.3.1.1 登记规则档案 —— 本模块不另建规则表。"
      >
        <div className="space-y-3">
          <div className="grid gap-2 md:grid-cols-3">
            <FormField id="editor-name" label="规则名" required>
              <Input
                id="editor-name"
                value={draft.name}
                onChange={(e) => patchDraft({ name: e.target.value })}
              />
            </FormField>
            <FormField id="editor-priority" label="优先级" hint="数字越小越先跑。">
              <Input
                id="editor-priority"
                type="number"
                value={draft.priority}
                onChange={(e) => patchDraft({ priority: Number(e.target.value) })}
              />
            </FormField>
            <FormField id="editor-dry-run" label="试运行">
              <select
                id="editor-dry-run"
                className={selectClass}
                value={draft.dryRun ? 'true' : 'false'}
                onChange={(e) => patchDraft({ dryRun: e.target.value === 'true' })}
              >
                <option value="true">试运行（不产生真实副作用）</option>
                <option value="false">真实执行</option>
              </select>
            </FormField>
          </div>

          {problems.length > 0 ? (
            <Notice tone="warning" title={`还有 ${problems.length} 处没配好`}>
              <ul className="list-disc space-y-1 pl-4">
                {problems.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            </Notice>
          ) : null}

          {actionError ? (
            <Notice tone="danger" title="操作没成功">
              {actionError}
            </Notice>
          ) : null}

          {savedHint ? (
            <Notice tone="success" title="已保存">
              {savedHint}
            </Notice>
          ) : null}

          <div className="flex items-center gap-2">
            <Button variant="brand" onClick={() => void onSave()} disabled={saving}>
              {saving ? <Loader2 className="animate-spin" aria-hidden /> : <Save aria-hidden />}
              保存规则
            </Button>
            {draft.conditions.length > 0 ? (
              <span className="text-muted text-sm">
                {describeCondition(draft.conditions[0]!, currentSchema)}
                {draft.conditions.length > 1 ? ` 等 ${draft.conditions.length} 条条件` : ''}
              </span>
            ) : null}
          </div>

          {engineGap ? (
            <p className="text-muted text-sm">
              ⚠ {engineGap.name}（{engineGap.moduleId}）尚未交付 · 关系 {engineGap.relation}
              {engineGap.note ? ` · ${engineGap.note}` : ''}
            </p>
          ) : null}
        </div>
      </SectionCard>
    </div>
  );
}
