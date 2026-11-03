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
  Activity,
  ArrowDown,
  ArrowUp,
  CircleAlert,
  FlaskConical,
  ListChecks,
  Loader2,
  Plus,
  RefreshCw,
  ScrollText,
  Trash2,
} from 'lucide-react';
import {
  ACTION_TYPES,
  CONDITION_OPERATORS,
  FAILURE_CLASSES,
  RUN_STATUSES,
  SCOPE_TYPES,
  disableRule,
  emptyExecutionForm,
  emptyHint,
  emptyRuleForm,
  enableRule,
  engineUnavailableNote,
  fetchApplicable,
  fetchCapabilities,
  fetchEventTypes,
  fetchOverview,
  fetchRule,
  fetchRules,
  fetchUpstreamShops,
  fetchUpstreamWarehouses,
  formatCount,
  formatDateTime,
  formFromRule,
  healthTone,
  lastRunText,
  previewRule,
  recordExecution,
  reorderRules,
  runStatusTone,
  saveRule,
  setDryRun,
  sideEffectText,
  toExecutionInput,
  toRuleInput,
  validateExecution,
  validateRule,
  type ExecutionForm,
  type RuleForm,
  type Tone,
} from './automation-rule-model';
import type {
  AutomationRuleApplicableRow,
  AutomationRuleCapabilities,
  AutomationRuleDetail,
  AutomationRuleEventTypeRow,
  AutomationRuleOverview,
  AutomationRulePreview,
  AutomationRuleRow,
  AutomationRuleUpstreamShopRow,
  AutomationRuleUpstreamWarehouseRow,
} from '../../../../../packages/api-contracts/src/index';

const selectClass =
  'border-border bg-background text-foreground h-9 w-full rounded-md border px-2 text-sm';

/** 模型的语气 → Badge 的语气（徽章只有 7 种 tone，没有 ok / warn / muted）。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

type FailingFilter = 'all' | 'failing' | 'dryRun';

/**
 * 18.3.1.1「规则列表」工作区。
 *
 * 🔴 页面上的四件事**不许**做错：
 *
 * 1. **最后一次执行**那一格必须显示**时间 / 结果 / 失败原因**三件套 ——
 *    失败原因不许被截断成「失败」两个字（蓝图加粗句的落点）。
 * 2. **坏规则**必须一眼可见：连续失败达阈值的规则带「连续失败」标记，
 *    顶部有一个**只看坏规则**的档位。
 * 3. **试运行**是与「启用 / 停用」**并列**的第二个标记，且写明「不产生真实副作用」。
 * 4. **执行引擎未交付**就说未交付：页面**不提供**「执行规则」按钮
 *    （一个点了必然失败的按钮会让运营以为「点一下就能跑」），
 *    只提供「登记一次执行结果」（本模块自己的统计台账）。
 */
export function AutomationRuleWorkspace() {
  const [capabilities, setCapabilities] = useState<AutomationRuleCapabilities | null>(null);
  const [overview, setOverview] = useState<AutomationRuleOverview | null>(null);
  const [rules, setRules] = useState<AutomationRuleRow[]>([]);
  const [eventTypes, setEventTypes] = useState<AutomationRuleEventTypeRow[]>([]);
  const [shops, setShops] = useState<AutomationRuleUpstreamShopRow[]>([]);
  const [warehouses, setWarehouses] = useState<AutomationRuleUpstreamWarehouseRow[]>([]);
  const [applicable, setApplicable] = useState<AutomationRuleApplicableRow[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [keyword, setKeyword] = useState('');
  const [eventType, setEventType] = useState('');
  const [failingFilter, setFailingFilter] = useState<FailingFilter>('all');

  const [form, setForm] = useState<RuleForm>(emptyRuleForm());
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  const [selected, setSelected] = useState<AutomationRuleDetail | null>(null);
  const [preview, setPreview] = useState<AutomationRulePreview | null>(null);
  const [previewPayload, setPreviewPayload] = useState('{\n  "order": {}\n}');
  const [executionForm, setExecutionForm] = useState<ExecutionForm>(emptyExecutionForm());
  const [executionErrors, setExecutionErrors] = useState<string[]>([]);

  const filtered = useMemo(
    () => keyword.trim() !== '' || eventType !== '' || failingFilter !== 'all',
    [keyword, eventType, failingFilter],
  );

  const loadRules = useCallback(async () => {
    const rows = await fetchRules({
      keyword: keyword.trim() || undefined,
      eventType: eventType || undefined,
      failing: failingFilter === 'failing' ? 'true' : undefined,
      dryRun: failingFilter === 'dryRun' ? 'true' : undefined,
    });
    setRules(rows);
  }, [keyword, eventType, failingFilter]);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ov, rows, events, shopRows, warehouseRows, applicableRows] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchRules({
          keyword: keyword.trim() || undefined,
          eventType: eventType || undefined,
          failing: failingFilter === 'failing' ? 'true' : undefined,
          dryRun: failingFilter === 'dryRun' ? 'true' : undefined,
        }),
        fetchEventTypes(),
        fetchUpstreamShops().catch(() => [] as AutomationRuleUpstreamShopRow[]),
        fetchUpstreamWarehouses().catch(() => [] as AutomationRuleUpstreamWarehouseRow[]),
        fetchApplicable().catch(() => [] as AutomationRuleApplicableRow[]),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setRules(rows);
      setEventTypes(events);
      setShops(shopRows);
      setWarehouses(warehouseRows);
      setApplicable(applicableRows);
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则列表读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [keyword, eventType, failingFilter]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const refresh = async () => {
    setLoading(true);
    try {
      await loadRules();
      setOverview(await fetchOverview());
      setNotice('已刷新');
    } catch (e) {
      setError(e instanceof Error ? e.message : '刷新失败');
    } finally {
      setLoading(false);
    }
  };

  const openRule = async (id: string) => {
    setNotice(null);
    try {
      const detail = await fetchRule(id);
      setSelected(detail);
      setPreview(null);
      setExecutionForm(emptyExecutionForm());
      setExecutionErrors([]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则详情读取失败');
    }
  };

  const submitRule = async () => {
    const errors = validateRule(form);
    setFormErrors(errors);
    if (errors.length > 0) return;
    setSaving(true);
    setNotice(null);
    try {
      const saved = await saveRule(toRuleInput(form));
      setSelected(saved);
      setForm(formFromRule(saved));
      setNotice(form.id ? '规则已更新' : '规则已登记');
      await loadRules();
      setOverview(await fetchOverview());
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async (rule: AutomationRuleRow) => {
    setNotice(null);
    try {
      if (rule.enabled) await disableRule(rule.id);
      else await enableRule(rule.id);
      await loadRules();
      setOverview(await fetchOverview());
      if (selected?.rule.id === rule.id) await openRule(rule.id);
      setNotice(rule.enabled ? '规则已停用（历史保留）' : '规则已启用');
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    }
  };

  const toggleDryRun = async (rule: AutomationRuleRow) => {
    setNotice(null);
    try {
      await setDryRun(rule.id, !rule.dryRun);
      await loadRules();
      setOverview(await fetchOverview());
      if (selected?.rule.id === rule.id) await openRule(rule.id);
      setNotice(rule.dryRun ? '已关闭试运行' : '已开启试运行：这条规则不产生真实副作用');
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    }
  };

  const move = async (rule: AutomationRuleRow, delta: number) => {
    const next = Math.min(1000, Math.max(1, rule.priority + delta));
    if (next === rule.priority) return;
    setNotice(null);
    try {
      await reorderRules({ entries: [{ ruleId: rule.id, priority: next }] });
      await loadRules();
      setNotice(`优先级已调整为 ${next}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : '排序失败');
    }
  };

  const runPreview = async () => {
    if (!selected) return;
    setNotice(null);
    try {
      setPreview(
        await previewRule(selected.rule.id, {
          payload: previewPayload,
          shopId: null,
          warehouseId: null,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '预演失败');
    }
  };

  const submitExecution = async () => {
    if (!selected) return;
    const errors = validateExecution(executionForm, selected.rule.dryRun);
    setExecutionErrors(errors);
    if (errors.length > 0) return;
    setNotice(null);
    try {
      await recordExecution(selected.rule.id, toExecutionInput(executionForm));
      await openRule(selected.rule.id);
      await loadRules();
      setOverview(await fetchOverview());
      setNotice('执行结果已登记到统计台账');
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记失败');
    }
  };

  const engineNote = engineUnavailableNote(capabilities ?? undefined);

  return (
    <div className="space-y-4">
      <PageHeader
        title="规则列表"
        description="管理自动化规则：触发事件、条件、动作、优先级、启用范围、试运行开关与执行统计。"
        actions={
          <>
            <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setForm(emptyRuleForm());
                setFormErrors([]);
                setSelected(null);
                setPreview(null);
              }}
            >
              <Plus className="h-4 w-4" />
              新建规则
            </Button>
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="读取失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title={notice}>
          {notice}
        </Notice>
      )}

      {engineNote && (
        <Notice tone="warning" title="规则执行引擎（18.3.2）尚未交付">
          {engineNote}
          <p className="mt-2">
            本页**不提供**「执行规则」按钮 —— 一个点了必然失败的按钮会让运营以为「点一下就能跑」。
            这里能用的是：规则档案、条件 / 动作 / 启用范围的配置、优先级排序、试运行开关，
            以及**执行结果登记**（本模块自己的统计台账）。
          </p>
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="规则总数"
          value={overview?.rules ?? 0}
          icon={<ListChecks className="h-4 w-4" />}
        />
        <StatCard
          label="启用中"
          value={overview?.enabledRules ?? 0}
          icon={<Activity className="h-4 w-4" />}
        />
        <StatCard
          label="试运行中"
          value={overview?.dryRunRules ?? 0}
          unit="条不产生真实副作用"
          icon={<FlaskConical className="h-4 w-4" />}
        />
        <StatCard
          label="连续失败（坏规则）"
          value={overview?.failingRules ?? 0}
          polarity="negative"
          icon={<CircleAlert className="h-4 w-4" />}
        />
      </div>

      <SectionCard
        title="规则列表"
        description="筛选与关键词都是真筛选（后端 SQL 过滤）。排序是全序：优先级 → 创建时间 → 主键，相同优先级次序确定。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">关键词</span>
              <Input
                aria-label="关键词"
                value={keyword}
                placeholder="规则名称 / 说明 / 触发事件"
                onChange={(e) => setKeyword(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">触发事件</span>
              <select
                aria-label="触发事件"
                className={selectClass}
                value={eventType}
                onChange={(e) => setEventType(e.target.value)}
              >
                <option value="">全部</option>
                {eventTypes.map((e) => (
                  <option key={`${e.eventType}#${e.schemaVersion}`} value={e.eventType}>
                    {e.eventType}（第 {e.schemaVersion} 版）
                  </option>
                ))}
              </select>
            </label>
            <div className="block space-y-1">
              <span className="text-label-13 text-foreground">快速档位</span>
              <Segmented
                aria-label="快速档位"
                value={failingFilter}
                onChange={(v) => setFailingFilter(v)}
                options={[
                  { value: 'all', label: '全部' },
                  { value: 'failing', label: '只看坏规则' },
                  { value: 'dryRun', label: '只看试运行' },
                ]}
              />
            </div>
            <div className="flex items-end">
              <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
                <RefreshCw className="h-4 w-4" />
                应用筛选
              </Button>
            </div>
          </div>

          {loading ? (
            <div className="text-copy-13 text-muted-foreground flex items-center gap-2 py-8">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取规则列表…
            </div>
          ) : rules.length === 0 ? (
            <EmptyState
              icon={<ListChecks className="h-8 w-8" />}
              title={filtered ? '没有符合条件的规则' : '本租户还没有登记过规则'}
              description={emptyHint(filtered)}
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full min-w-[1080px] text-left">
                <thead className="text-label-13 text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">规则</th>
                    <th className="px-3 py-2 font-medium">触发事件</th>
                    <th className="px-3 py-2 font-medium">优先级</th>
                    <th className="px-3 py-2 font-medium">状态</th>
                    <th className="px-3 py-2 font-medium">试运行</th>
                    <th className="px-3 py-2 font-medium">最后一次执行</th>
                    <th className="px-3 py-2 font-medium">连续失败</th>
                    <th className="px-3 py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((rule) => (
                    <tr key={rule.id} className="border-border border-t">
                      <td className="px-3 py-2">
                        <button
                          type="button"
                          className="text-foreground hover:underline"
                          onClick={() => void openRule(rule.id)}
                        >
                          {rule.name}
                        </button>
                        <div className="text-muted-foreground">
                          {rule.conditionCount} 条件 · {rule.actionCount} 动作 · {rule.scopeCount}{' '}
                          范围
                        </div>
                      </td>
                      <td className="px-3 py-2">{rule.triggerEventType}</td>
                      <td className="px-3 py-2">{rule.priority}</td>
                      <td className="px-3 py-2">
                        <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                          {rule.statusLabel}
                        </Badge>
                        <Badge tone={BADGE_TONE[healthTone(rule.health)]} className="ml-1">
                          {rule.healthLabel}
                        </Badge>
                      </td>
                      <td className="px-3 py-2">
                        {rule.dryRun ? (
                          <Badge tone="warning">{sideEffectText(rule)}</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <Badge tone={BADGE_TONE[runStatusTone(rule.lastRunStatus)]}>
                          {rule.lastRunStatusLabel || '尚未执行'}
                        </Badge>
                        <div className="text-muted-foreground">{lastRunText(rule)}</div>
                      </td>
                      <td className="px-3 py-2">
                        {rule.failing ? (
                          <Badge tone="danger">
                            连续失败 {rule.consecutiveFailureCount} /{' '}
                            {rule.consecutiveFailureThreshold}
                          </Badge>
                        ) : (
                          <span className="text-muted-foreground">
                            {rule.consecutiveFailureCount} / {rule.consecutiveFailureThreshold}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void openRule(rule.id)}
                          >
                            详情
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void toggleEnabled(rule)}
                          >
                            {rule.enabled ? '停用' : '启用'}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void toggleDryRun(rule)}
                          >
                            {rule.dryRun ? '关闭试运行' : '开启试运行'}
                          </Button>
                          <Button size="sm" variant="ghost" onClick={() => void move(rule, -1)}>
                            <ArrowUp className="h-3 w-3" />
                          </Button>
                          <Button size="sm" variant="ghost" onClick={() => void move(rule, 1)}>
                            <ArrowDown className="h-3 w-3" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </SectionCard>

      <SectionCard
        title={form.id ? '编辑规则' : '新建规则'}
        description="条件 / 动作 / 启用范围整体替换：保存后这条规则「现在长什么样」是确定的。触发事件必须在事件注册表里登记过。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="rule-name" label="规则名称" required>
              <Input
                id="rule-name"
                value={form.name}
                placeholder="例如：订单超库存就通知"
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </FormField>
            <FormField id="rule-event" label="触发事件" required>
              <select
                id="rule-event"
                className={selectClass}
                value={form.triggerEventType}
                onChange={(e) => setForm({ ...form, triggerEventType: e.target.value })}
              >
                <option value="">请选择</option>
                {eventTypes
                  .filter((e) => e.status === 'ACTIVE')
                  .map((e) => (
                    <option key={`${e.eventType}#${e.schemaVersion}`} value={e.eventType}>
                      {e.eventType}（第 {e.schemaVersion} 版）
                    </option>
                  ))}
              </select>
            </FormField>
            <FormField id="rule-priority" label="优先级" hint="数字越小越先执行（1–1000）" required>
              <Input
                id="rule-priority"
                value={form.priority}
                inputMode="numeric"
                onChange={(e) => setForm({ ...form, priority: e.target.value })}
              />
            </FormField>
          </div>

          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="rule-desc" label="规则说明">
              <Input
                id="rule-desc"
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </FormField>
            <FormField
              id="rule-threshold"
              label="连续失败阈值"
              hint="连续失败达到这个数即判为坏规则"
              required
            >
              <Input
                id="rule-threshold"
                value={form.consecutiveFailureThreshold}
                inputMode="numeric"
                onChange={(e) => setForm({ ...form, consecutiveFailureThreshold: e.target.value })}
              />
            </FormField>
            <FormField
              id="rule-dry-run"
              label="试运行开关"
              hint="独立于启用 / 停用；开启后不产生真实副作用"
            >
              <label className="flex h-9 items-center gap-2">
                <input
                  id="rule-dry-run"
                  type="checkbox"
                  checked={form.dryRun}
                  onChange={(e) => setForm({ ...form, dryRun: e.target.checked })}
                />
                <span className="text-copy-13">
                  {form.dryRun ? '试运行 · 不产生真实副作用' : '真实执行'}
                </span>
              </label>
            </FormField>
          </div>

          {/* 条件 */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="text-h-15">条件（过滤）</h4>
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setForm({
                    ...form,
                    conditions: [
                      ...form.conditions,
                      {
                        fieldPath: '',
                        operator: 'EQ',
                        valueText: '',
                        valueNumber: '',
                        enabled: true,
                      },
                    ],
                  })
                }
              >
                <Plus className="h-3 w-3" />
                加条件
              </Button>
            </div>
            {form.conditions.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                没有条件 = 事件一来就命中。条件之间是「与」的关系。
              </p>
            ) : (
              form.conditions.map((condition, index) => (
                <div
                  key={index}
                  className="border-border grid gap-2 rounded-md border p-3 md:grid-cols-5"
                >
                  <Input
                    aria-label={`条件 ${index + 1} 字段路径`}
                    placeholder="order.totalAmount"
                    value={condition.fieldPath}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        conditions: form.conditions.map((c, i) =>
                          i === index ? { ...c, fieldPath: e.target.value } : c,
                        ),
                      })
                    }
                  />
                  <select
                    aria-label={`条件 ${index + 1} 操作符`}
                    className={selectClass}
                    value={condition.operator}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        conditions: form.conditions.map((c, i) =>
                          i === index ? { ...c, operator: e.target.value } : c,
                        ),
                      })
                    }
                  >
                    {CONDITION_OPERATORS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                  <Input
                    aria-label={`条件 ${index + 1} 文本值`}
                    placeholder="文本值（IN 用逗号分隔）"
                    value={condition.valueText}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        conditions: form.conditions.map((c, i) =>
                          i === index ? { ...c, valueText: e.target.value } : c,
                        ),
                      })
                    }
                  />
                  <Input
                    aria-label={`条件 ${index + 1} 数值`}
                    placeholder="数值"
                    value={condition.valueNumber}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        conditions: form.conditions.map((c, i) =>
                          i === index ? { ...c, valueNumber: e.target.value } : c,
                        ),
                      })
                    }
                  />
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      setForm({
                        ...form,
                        conditions: form.conditions.filter((_, i) => i !== index),
                      })
                    }
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              ))
            )}
          </div>

          {/* 动作 */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="text-h-15">动作（多步，按顺序执行）</h4>
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setForm({
                    ...form,
                    actions: [
                      ...form.actions,
                      {
                        actionType: 'SEND_NOTIFICATION',
                        targetRef: '',
                        params: '{}',
                        enabled: true,
                      },
                    ],
                  })
                }
              >
                <Plus className="h-3 w-3" />
                加动作
              </Button>
            </div>
            {form.actions.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有动作。动作的参数必须是一个 JSON 对象。
              </p>
            ) : (
              form.actions.map((action, index) => (
                <div
                  key={index}
                  className="border-border grid gap-2 rounded-md border p-3 md:grid-cols-4"
                >
                  <select
                    aria-label={`动作 ${index + 1} 类型`}
                    className={selectClass}
                    value={action.actionType}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        actions: form.actions.map((a, i) =>
                          i === index ? { ...a, actionType: e.target.value } : a,
                        ),
                      })
                    }
                  >
                    {ACTION_TYPES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                  <Input
                    aria-label={`动作 ${index + 1} 目标`}
                    placeholder="目标（模板码 / 对象类型 / 接口路径）"
                    value={action.targetRef}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        actions: form.actions.map((a, i) =>
                          i === index ? { ...a, targetRef: e.target.value } : a,
                        ),
                      })
                    }
                  />
                  <Input
                    aria-label={`动作 ${index + 1} 参数`}
                    placeholder='{"channel":"EMAIL"}'
                    value={action.params}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        actions: form.actions.map((a, i) =>
                          i === index ? { ...a, params: e.target.value } : a,
                        ),
                      })
                    }
                  />
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      setForm({
                        ...form,
                        actions: form.actions.filter((_, i) => i !== index),
                      })
                    }
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              ))
            )}
          </div>

          {/* 启用范围 */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="text-h-15">启用范围</h4>
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setForm({
                    ...form,
                    scopes: [...form.scopes, { scopeType: 'TENANT', scopeRef: '', scopeLabel: '' }],
                  })
                }
              >
                <Plus className="h-3 w-3" />
                加范围
              </Button>
            </div>
            <p className="text-copy-13 text-muted-foreground">
              启用范围是**真约束**：范围之外，这条规则不会出现在「范围内生效的规则」里。
            </p>
            {form.scopes.map((scope, index) => (
              <div
                key={index}
                className="border-border grid gap-2 rounded-md border p-3 md:grid-cols-4"
              >
                <select
                  aria-label={`范围 ${index + 1} 类别`}
                  className={selectClass}
                  value={scope.scopeType}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      scopes: form.scopes.map((s, i) =>
                        i === index
                          ? { ...s, scopeType: e.target.value, scopeRef: '', scopeLabel: '' }
                          : s,
                      ),
                    })
                  }
                >
                  {SCOPE_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
                {scope.scopeType === 'SHOP' ? (
                  <select
                    aria-label={`范围 ${index + 1} 店铺`}
                    className={selectClass}
                    value={scope.scopeRef}
                    onChange={(e) => {
                      const shop = shops.find((s) => s.id === e.target.value);
                      setForm({
                        ...form,
                        scopes: form.scopes.map((s, i) =>
                          i === index
                            ? {
                                ...s,
                                scopeRef: e.target.value,
                                scopeLabel: shop ? `${shop.code} ${shop.name}` : '',
                              }
                            : s,
                        ),
                      });
                    }}
                  >
                    <option value="">请选择店铺</option>
                    {shops.map((shop) => (
                      <option key={shop.id} value={shop.id}>
                        {shop.code} {shop.name}
                      </option>
                    ))}
                  </select>
                ) : scope.scopeType === 'WAREHOUSE' ? (
                  <select
                    aria-label={`范围 ${index + 1} 仓库`}
                    className={selectClass}
                    value={scope.scopeRef}
                    onChange={(e) => {
                      const wh = warehouses.find((w) => w.id === e.target.value);
                      setForm({
                        ...form,
                        scopes: form.scopes.map((s, i) =>
                          i === index
                            ? {
                                ...s,
                                scopeRef: e.target.value,
                                scopeLabel: wh ? `${wh.code} ${wh.name}` : '',
                              }
                            : s,
                        ),
                      });
                    }}
                  >
                    <option value="">请选择仓库</option>
                    {warehouses.map((wh) => (
                      <option key={wh.id} value={wh.id}>
                        {wh.code} {wh.name}
                      </option>
                    ))}
                  </select>
                ) : (
                  <Input
                    aria-label={`范围 ${index + 1} 名称`}
                    placeholder="范围名称（整个租户）"
                    value={scope.scopeLabel}
                    onChange={(e) =>
                      setForm({
                        ...form,
                        scopes: form.scopes.map((s, i) =>
                          i === index ? { ...s, scopeLabel: e.target.value } : s,
                        ),
                      })
                    }
                  />
                )}
                <Input
                  aria-label={`范围 ${index + 1} 名称`}
                  placeholder="显示名称"
                  value={scope.scopeLabel}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      scopes: form.scopes.map((s, i) =>
                        i === index ? { ...s, scopeLabel: e.target.value } : s,
                      ),
                    })
                  }
                />
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    setForm({
                      ...form,
                      scopes: form.scopes.filter((_, i) => i !== index),
                    })
                  }
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            ))}
          </div>

          {formErrors.length > 0 && (
            <Notice tone="danger" title="表单还有几处要改">
              <ul className="list-disc pl-5">
                {formErrors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            </Notice>
          )}

          <div className="flex gap-2">
            <Button onClick={() => void submitRule()} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              {form.id ? '保存修改' : '登记规则'}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setForm(emptyRuleForm());
                setFormErrors([]);
              }}
            >
              清空表单
            </Button>
          </div>
        </div>
      </SectionCard>

      {selected && (
        <SectionCard
          title={`规则详情 · ${selected.rule.name}`}
          description={`触发事件 ${selected.rule.triggerEventType} · 优先级 ${selected.rule.priority} · ${selected.rule.statusLabel}`}
          extra={
            <>
              {selected.rule.dryRun && (
                <Badge tone="warning">{sideEffectText(selected.rule)}</Badge>
              )}
              <Badge tone={BADGE_TONE[healthTone(selected.rule.health)]}>
                {selected.rule.healthLabel}
              </Badge>
              <Badge tone={BADGE_TONE[runStatusTone(selected.rule.lastRunStatus)]}>
                最后一次执行：{selected.rule.lastRunStatusLabel || '尚未执行'}
              </Badge>
            </>
          }
        >
          <div className="space-y-4">
            <div className="border-border rounded-md border p-3">
              <h4 className="text-h-15">最后一次执行</h4>
              <p className="text-copy-13">
                {formatDateTime(selected.rule.lastRunAt)} ·{' '}
                {selected.rule.lastRunStatusLabel || '尚未执行'}
              </p>
              {selected.rule.lastRunFailureReason && (
                <p className="text-danger text-copy-13">
                  失败原因：{selected.rule.lastRunFailureReason}
                </p>
              )}
              <p className="text-muted-foreground text-copy-13">
                累计执行 {formatCount(selected.rule.runCount)} 次 · 成功{' '}
                {formatCount(selected.rule.successCount)} 次 · 失败{' '}
                {formatCount(selected.rule.failureCount)} 次 · 连续失败{' '}
                {selected.rule.consecutiveFailureCount} /{' '}
                {selected.rule.consecutiveFailureThreshold}
              </p>
            </div>

            <div className="grid gap-3 md:grid-cols-3">
              <div>
                <h4 className="text-h-15">条件（{selected.conditions.length}）</h4>
                {selected.conditions.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">没有条件 = 事件一来就命中。</p>
                ) : (
                  <ul className="text-copy-13 space-y-1">
                    {selected.conditions.map((c) => (
                      <li key={c.id}>
                        {c.fieldPath} {c.operatorLabel}{' '}
                        {c.valueNumber !== null ? c.valueNumber : c.valueText}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h4 className="text-h-15">动作（{selected.actions.length}）</h4>
                {selected.actions.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">还没有动作。</p>
                ) : (
                  <ul className="text-copy-13 space-y-1">
                    {selected.actions.map((a) => (
                      <li key={a.id}>
                        {a.seq}. {a.actionTypeLabel} → {a.targetRef || '（无目标）'}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h4 className="text-h-15">启用范围（{selected.scopes.length}）</h4>
                {selected.scopes.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">
                    没有范围 —— 这条规则不对任何店铺 / 仓库生效。
                  </p>
                ) : (
                  <ul className="text-copy-13 space-y-1">
                    {selected.scopes.map((s) => (
                      <li key={s.id}>
                        {s.scopeTypeLabel}
                        {s.scopeLabel ? ` · ${s.scopeLabel}` : ''}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            <div className="space-y-2">
              <h4 className="text-h-15">预演（求值，不执行）</h4>
              <p className="text-copy-13 text-muted-foreground">
                按样本载荷真的求值条件、真的判启用范围，给出动作计划。
                <strong>本端点不执行任何动作</strong> —— 执行引擎归 18.3.2，尚未交付。
              </p>
              <textarea
                aria-label="样本载荷"
                className="border-border bg-background text-foreground min-h-24 w-full rounded-md border p-2 text-sm"
                value={previewPayload}
                onChange={(e) => setPreviewPayload(e.target.value)}
              />
              <Button size="sm" variant="outline" onClick={() => void runPreview()}>
                <FlaskConical className="h-4 w-4" />
                预演
              </Button>
              {preview && (
                <div className="border-border space-y-2 rounded-md border p-3">
                  <p className="text-copy-13">
                    范围：{preview.scopeApplicable ? '命中' : '未命中'} · {preview.scopeNote}
                  </p>
                  <p className="text-copy-13">
                    条件命中 {preview.matchedConditions} / {preview.totalConditions} ·{' '}
                    {preview.conditionsMatched ? '全部命中' : '未全部命中'}
                  </p>
                  <p className="text-copy-13">副作用模式：{preview.sideEffectModeLabel}</p>
                  <ul className="text-copy-13 space-y-1">
                    {preview.conditionEvaluations.map((e) => (
                      <li key={e.seq}>
                        {e.fieldPath} {e.operatorLabel} {e.expectedValue} → 实际{' '}
                        {e.actualValue || '（无）'} · {e.matched ? '命中' : `未命中（${e.reason}）`}
                      </li>
                    ))}
                  </ul>
                  <p className="text-copy-13">
                    动作计划：{preview.plannedActions.length} 步 ·{' '}
                    {preview.wouldProduceSideEffects ? '会产生副作用' : '不产生副作用'}
                  </p>
                  <p className="text-muted-foreground text-copy-13">{preview.note}</p>
                </div>
              )}
            </div>

            <div className="space-y-2">
              <h4 className="text-h-15">登记一次执行结果</h4>
              <p className="text-copy-13 text-muted-foreground">
                执行引擎未交付 ⇒ 这一格是**人工登记**：登记后规则档案上的「最后一次执行」与
                连续失败计数会同步更新。
                {selected.rule.dryRun && '这条规则处于试运行：不允许登记真实副作用。'}
              </p>
              <div className="grid gap-3 md:grid-cols-4">
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">执行结论</span>
                  <select
                    aria-label="执行结论"
                    className={selectClass}
                    value={executionForm.status}
                    onChange={(e) => setExecutionForm({ ...executionForm, status: e.target.value })}
                  >
                    {RUN_STATUSES.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">失败分类</span>
                  <select
                    aria-label="失败分类"
                    className={selectClass}
                    value={executionForm.failureClass}
                    onChange={(e) =>
                      setExecutionForm({ ...executionForm, failureClass: e.target.value })
                    }
                  >
                    <option value="">（无）</option>
                    {FAILURE_CLASSES.map((c) => (
                      <option key={c.value} value={c.value}>
                        {c.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">失败原因</span>
                  <Input
                    aria-label="失败原因"
                    value={executionForm.failureReason}
                    onChange={(e) =>
                      setExecutionForm({ ...executionForm, failureReason: e.target.value })
                    }
                  />
                </label>
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">触发来源</span>
                  <Input
                    aria-label="触发来源"
                    value={executionForm.triggeredBy}
                    placeholder="order.created"
                    onChange={(e) =>
                      setExecutionForm({ ...executionForm, triggeredBy: e.target.value })
                    }
                  />
                </label>
              </div>
              <div className="grid gap-3 md:grid-cols-3">
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">计划动作数</span>
                  <Input
                    aria-label="计划动作数"
                    value={executionForm.plannedActions}
                    inputMode="numeric"
                    onChange={(e) =>
                      setExecutionForm({ ...executionForm, plannedActions: e.target.value })
                    }
                  />
                </label>
                <label className="block space-y-1">
                  <span className="text-label-13 text-foreground">已执行动作数</span>
                  <Input
                    aria-label="已执行动作数"
                    value={executionForm.executedActions}
                    inputMode="numeric"
                    onChange={(e) =>
                      setExecutionForm({ ...executionForm, executedActions: e.target.value })
                    }
                  />
                </label>
                <label className="flex h-9 items-end gap-2">
                  <input
                    aria-label="产生了真实副作用"
                    type="checkbox"
                    checked={executionForm.sideEffectsApplied}
                    onChange={(e) =>
                      setExecutionForm({
                        ...executionForm,
                        sideEffectsApplied: e.target.checked,
                      })
                    }
                  />
                  <span className="text-copy-13">产生了真实副作用</span>
                </label>
              </div>
              {executionErrors.length > 0 && (
                <Notice tone="danger" title="登记还有几处要改">
                  <ul className="list-disc pl-5">
                    {executionErrors.map((e) => (
                      <li key={e}>{e}</li>
                    ))}
                  </ul>
                </Notice>
              )}
              <Button size="sm" onClick={() => void submitExecution()}>
                <ScrollText className="h-4 w-4" />
                登记执行结果
              </Button>
            </div>

            <div className="space-y-2">
              <h4 className="text-h-15">执行历史（{selected.recentExecutions.length}）</h4>
              {selected.recentExecutions.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">
                  这条规则还没有执行记录 —— 「没跑过」不等于「坏了」。
                </p>
              ) : (
                <ul className="text-copy-13 space-y-1">
                  {selected.recentExecutions.map((e) => (
                    <li key={e.id}>
                      #{e.runNo} · {formatDateTime(e.finishedAt)} · {e.statusLabel} ·{' '}
                      {e.sourceLabel}
                      {e.dryRun ? ' · 试运行' : ''}
                      {e.sideEffectsApplied ? ' · 已产生副作用' : ' · 未产生副作用'}
                      {e.failureReason ? ` · ${e.failureReason}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="能力与边界"
        description="哪些真接通、哪些还没交付、缺了归谁 —— 逐条写明依据。"
      >
        <div className="space-y-3">
          <ul className="space-y-2">
            {(capabilities?.capabilities ?? []).map((c) => (
              <li key={`${c.moduleId}-${c.name}`} className="border-border rounded-md border p-3">
                <div className="flex items-center gap-2">
                  <Badge tone={c.available ? 'success' : 'warning'}>
                    {c.available ? '已交付' : '未交付'}
                  </Badge>
                  <span className="text-foreground text-copy-13">{c.name}</span>
                </div>
                <p className="text-muted-foreground text-copy-13 mt-1">{c.evidence}</p>
                {c.note && <p className="text-muted-foreground text-copy-13 mt-1">{c.note}</p>}
              </li>
            ))}
          </ul>
          <div>
            <h4 className="text-h-15">下游缺口</h4>
            <ul className="text-copy-13 space-y-1">
              {(capabilities?.gaps ?? []).map((g) => (
                <li key={g.moduleId}>
                  <Badge tone={g.available ? 'success' : 'warning'}>{g.moduleId}</Badge> {g.name} ——{' '}
                  {g.relation} · {g.note}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        title="范围内生效的规则"
        description="启用范围是真约束：这一段列出**当前租户 + 整个租户范围**下生效的规则。"
      >
        {applicable.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">
            当前没有「整个租户」范围内生效的规则 —— 范围是**真约束**，没有范围行就不生效。
          </p>
        ) : (
          <ul className="text-copy-13 space-y-1">
            {applicable.map((a) => (
              <li key={a.ruleId}>
                {a.ruleName} · 优先级 {a.priority} · 命中范围 {a.matchedScopeTypeLabel}
                {a.matchedScopeLabel ? ` · ${a.matchedScopeLabel}` : ''}
                {a.dryRun ? ' · 试运行' : ''}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}
