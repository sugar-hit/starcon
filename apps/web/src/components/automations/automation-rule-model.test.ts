import { describe, expect, it } from 'vitest';
import {
  emptyExecutionForm,
  emptyHint,
  emptyRuleForm,
  engineUnavailableNote,
  formFromRule,
  healthTone,
  lastRunText,
  runStatusTone,
  sideEffectText,
  toExecutionInput,
  toRuleInput,
  validateExecution,
  validateRule,
  type RuleForm,
} from './automation-rule-model';
import type { AutomationRuleDetail } from '../../../../../packages/api-contracts/src/index';

/**
 * 18.3.1.1「规则列表」前端模型的**纯函数**测试。
 *
 * 🔴 这几格是本模块红线在页面上的落点，单独钉住：
 *   · 最后一次执行必须显示**时间 / 结果 / 失败原因**三件套（不是布尔位）；
 *   · 坏规则（连续失败）必须与「不稳定」**分开**（一个 danger、一个 warn）；
 *   · 试运行**不产生真实副作用** —— 表单层先挡一道，与后端 409 同一道纪律。
 */

function form(overrides: Partial<RuleForm> = {}): RuleForm {
  return { ...emptyRuleForm(), ...overrides };
}

describe('18.3.1.1 规则列表 · 前端模型', () => {
  it('「最后一次执行」那一格显示时间 / 结果 / 失败原因三件套', () => {
    expect(
      lastRunText({
        lastRunAt: '2026-10-30T04:00:00Z',
        lastRunStatus: 'FAILED',
        lastRunStatusLabel: '失败',
        lastRunFailureReason: '下游接口返回 500',
      }),
    ).toContain('失败');
    expect(
      lastRunText({
        lastRunAt: '2026-10-30T04:00:00Z',
        lastRunStatus: 'FAILED',
        lastRunStatusLabel: '失败',
        lastRunFailureReason: '下游接口返回 500',
      }),
    ).toContain('下游接口返回 500');
    // 还没跑过 ⇒ 「尚未执行」，不是「成功」也不是空串。
    expect(lastRunText({ lastRunAt: null, lastRunStatus: '' })).toBe('尚未执行');
  });

  it('坏规则与不稳定是**两个语气**，试运行是第三个', () => {
    expect(healthTone('FAILING')).toBe('danger');
    expect(healthTone('DEGRADED')).toBe('warn');
    expect(healthTone('HEALTHY')).toBe('ok');
    expect(healthTone('NEVER_RUN')).toBe('muted');
    expect(runStatusTone('FAILED')).toBe('danger');
    expect(runStatusTone('SUCCEEDED')).toBe('ok');
    expect(sideEffectText({ dryRun: true })).toContain('不产生副作用');
    expect(sideEffectText({ dryRun: false })).toBe('真实执行');
  });

  it('空态区分「还没登记过」与「筛选无结果」', () => {
    expect(emptyHint(false)).toContain('还没有登记过');
    expect(emptyHint(true)).toContain('筛选');
  });

  it('执行引擎未交付 ⇒ 如实给出说明；已交付 ⇒ 不给', () => {
    expect(
      engineUnavailableNote({
        capabilities: [
          {
            moduleId: '18.3.1.1',
            name: '🔴 规则执行',
            available: false,
            evidence: '本仓不存在规则执行引擎',
            ownerModule: '18.3.2',
            note: '端点如实答「执行引擎未交付」',
          },
        ],
        gaps: [],
      }),
    ).toContain('执行引擎未交付');
    expect(
      engineUnavailableNote({
        capabilities: [
          {
            moduleId: '18.3.1.1',
            name: '🔴 规则执行',
            available: true,
            evidence: '已交付',
            ownerModule: '',
            note: '',
          },
        ],
        gaps: [],
      }),
    ).toBeNull();
    expect(engineUnavailableNote(undefined)).toBeNull();
  });

  it('表单校验是**真校验**：名称 / 触发事件 / 优先级 / 阈值都要', () => {
    const errors = validateRule(form());
    expect(errors.join('|')).toContain('规则名称');
    expect(errors.join('|')).toContain('触发事件');

    const ok = validateRule(form({ name: '订单超库存就通知', triggerEventType: 'order.created' }));
    expect(ok).toEqual([]);
  });

  it('大小比较必须给数值；区间必须两个值；属于必须有候选', () => {
    const errors = validateRule(
      form({
        name: 'r',
        triggerEventType: 'order.created',
        conditions: [
          {
            fieldPath: 'order.totalAmount',
            operator: 'GT',
            valueText: '',
            valueNumber: '',
            enabled: true,
          },
          {
            fieldPath: 'order.amount',
            operator: 'BETWEEN',
            valueText: '100',
            valueNumber: '',
            enabled: true,
          },
          {
            fieldPath: 'order.status',
            operator: 'IN',
            valueText: '',
            valueNumber: '',
            enabled: true,
          },
        ],
      }),
    );
    expect(errors.join('|')).toContain('必须给一个数值');
    expect(errors.join('|')).toContain('~');
    expect(errors.join('|')).toContain('候选值');
  });

  it('店铺 / 仓库范围必须带引用；租户范围不能带引用', () => {
    const errors = validateRule(
      form({
        name: 'r',
        triggerEventType: 'order.created',
        scopes: [
          { scopeType: 'SHOP', scopeRef: '', scopeLabel: '' },
          { scopeType: 'TENANT', scopeRef: '00000000-0000-4000-8000-000000000000', scopeLabel: '' },
        ],
      }),
    );
    expect(errors.join('|')).toContain('必须选一个对象');
    expect(errors.join('|')).toContain('不能带引用');
  });

  it('动作参数必须是 JSON 对象', () => {
    const errors = validateRule(
      form({
        name: 'r',
        triggerEventType: 'order.created',
        actions: [
          { actionType: 'SEND_NOTIFICATION', targetRef: 'tpl', params: '[1,2]', enabled: true },
        ],
      }),
    );
    expect(errors.join('|')).toContain('JSON 对象');
  });

  it('表单翻成入参：条件 / 动作按 seq 排序，范围不带引用时给 null', () => {
    const input = toRuleInput(
      form({
        name: '  规则 A  ',
        triggerEventType: ' order.created ',
        conditions: [
          {
            fieldPath: ' order.totalAmount ',
            operator: 'GT',
            valueText: '',
            valueNumber: '100.5',
            enabled: true,
          },
        ],
        actions: [
          { actionType: 'SEND_NOTIFICATION', targetRef: ' tpl ', params: '{"a":1}', enabled: true },
        ],
        scopes: [{ scopeType: 'TENANT', scopeRef: '', scopeLabel: ' 整个租户 ' }],
      }),
    );
    expect(input.name).toBe('规则 A');
    expect(input.triggerEventType).toBe('order.created');
    expect(input.conditions?.[0]?.seq).toBe(1);
    expect(input.conditions?.[0]?.valueNumber).toBe(100.5);
    expect(input.actions?.[0]?.seq).toBe(1);
    expect(input.scopes?.[0]?.scopeRef).toBeNull();
  });

  it('🔴 试运行规则不许登记真实副作用，也不许登记已执行动作', () => {
    const withSideEffect = validateExecution(
      {
        ...emptyExecutionForm(),
        sideEffectsApplied: true,
        executedActions: '1',
        plannedActions: '1',
      },
      true,
    );
    expect(withSideEffect.join('|')).toContain('试运行');
    const withExecuted = validateExecution(
      {
        ...emptyExecutionForm(),
        sideEffectsApplied: false,
        executedActions: '2',
        plannedActions: '2',
      },
      true,
    );
    expect(withExecuted.join('|')).toContain('试运行');
    // 非试运行规则：登记真实副作用是允许的。
    expect(
      validateExecution(
        {
          ...emptyExecutionForm(),
          sideEffectsApplied: true,
          executedActions: '1',
          plannedActions: '1',
        },
        false,
      ),
    ).toEqual([]);
  });

  it('失败必须写清失败原因，且失败原因与失败分类配套', () => {
    const noReason = validateExecution({ ...emptyExecutionForm(), status: 'FAILED' }, false);
    expect(noReason.join('|')).toContain('失败原因');
    const halfPair = validateExecution(
      { ...emptyExecutionForm(), status: 'FAILED', failureReason: '下游 500' },
      false,
    );
    expect(halfPair.join('|')).toContain('失败分类');
    const ok = validateExecution(
      {
        ...emptyExecutionForm(),
        status: 'FAILED',
        failureReason: '下游 500',
        failureClass: 'ACTION_FAILED',
      },
      false,
    );
    expect(ok).toEqual([]);
  });

  it('已执行动作数不能超过计划动作数；声称没产生副作用却执行了动作 ⇒ 报错', () => {
    expect(
      validateExecution(
        { ...emptyExecutionForm(), plannedActions: '1', executedActions: '2' },
        false,
      ).join('|'),
    ).toContain('不能超过');
    expect(
      validateExecution(
        {
          ...emptyExecutionForm(),
          plannedActions: '2',
          executedActions: '1',
          sideEffectsApplied: false,
        },
        false,
      ).join('|'),
    ).toContain('真实副作用');
  });

  it('登记入参如实标「人工登记」', () => {
    const input = toExecutionInput({
      ...emptyExecutionForm(),
      status: 'SUCCEEDED',
      plannedActions: '2',
      executedActions: '2',
      sideEffectsApplied: true,
    });
    expect(input.source).toBe('MANUAL');
    expect(input.plannedActions).toBe(2);
    expect(input.executedActions).toBe(2);
    expect(input.sideEffectsApplied).toBe(true);
  });

  it('由规则档案回填表单（编辑用）', () => {
    const detail = {
      rule: {
        id: 'rule-1',
        name: '规则 A',
        description: '说明',
        triggerEventType: 'order.created',
        triggerSchemaVersion: 1,
        priority: 42,
        enabled: true,
        status: 'ACTIVE',
        statusLabel: '生效',
        dryRun: true,
        sideEffectMode: 'SIMULATE',
        sideEffectModeLabel: '试运行 · 不产生副作用',
        consecutiveFailureThreshold: 5,
        conditionCount: 1,
        actionCount: 1,
        scopeCount: 1,
        lastRunAt: null,
        lastRunStatus: '',
        lastRunStatusLabel: '',
        lastRunFailureReason: '',
        lastRunExecutionId: null,
        runCount: 0,
        successCount: 0,
        failureCount: 0,
        consecutiveFailureCount: 0,
        failing: false,
        failingSince: null,
        health: 'NEVER_RUN',
        healthLabel: '未运行',
        remark: '备注',
        createdBy: 'u',
        createdAt: '2026-10-30T00:00:00Z',
        updatedAt: '2026-10-30T00:00:00Z',
      },
      conditions: [
        {
          id: 'c1',
          seq: 1,
          fieldPath: 'order.totalAmount',
          operator: 'GT',
          operatorLabel: '大于',
          valueText: '',
          valueNumber: 100,
          valueBool: null,
          enabled: true,
          remark: '',
        },
      ],
      actions: [
        {
          id: 'a1',
          seq: 1,
          actionType: 'SEND_NOTIFICATION',
          actionTypeLabel: '发送通知',
          targetRef: 'tpl.low',
          params: '{"channel":"EMAIL"}',
          enabled: true,
          remark: '',
        },
      ],
      scopes: [
        {
          id: 's1',
          scopeType: 'TENANT',
          scopeTypeLabel: '整个租户',
          scopeRef: null,
          scopeLabel: '整个租户',
        },
      ],
      recentExecutions: [],
    } as unknown as AutomationRuleDetail;

    const restored = formFromRule(detail);
    expect(restored.id).toBe('rule-1');
    expect(restored.priority).toBe('42');
    expect(restored.consecutiveFailureThreshold).toBe('5');
    expect(restored.dryRun).toBe(true);
    expect(restored.conditions).toHaveLength(1);
    expect(restored.conditions[0]?.valueNumber).toBe('100');
    expect(restored.actions[0]?.params).toBe('{"channel":"EMAIL"}');
    expect(restored.scopes[0]?.scopeRef).toBe('');
    // 回填后的表单仍然是**合法**的（可以直接保存）。
    expect(validateRule(restored)).toEqual([]);
  });
});
