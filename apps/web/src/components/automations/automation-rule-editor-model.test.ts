import { describe, expect, it } from 'vitest';
import {
  buildRuleInput,
  buildTestInput,
  describeCondition,
  draftFromDetail,
  emptyDraft,
  fieldByPath,
  nextRunNo,
  parseSamplePayload,
  summarizeTestResult,
  validateDraft,
  validateParamsJson,
  type ActionDraft,
  type ConditionDraft,
  type EditorDraft,
} from './automation-rule-editor-model';
import type {
  AutomationRuleDetail,
  AutomationRuleEditorActionCatalog,
  AutomationRuleEditorApiCatalog,
  AutomationRuleEditorEventSchema,
  AutomationRuleEditorTestResult,
  AutomationRuleEditorTestRunRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.3.1.2「规则编辑器」前端模型的**纯函数**测试。
 *
 * 🔴 这几格是本模块红线在页面上的落点，单独钉住：
 *   · 字段 / 操作符 / 必填参数的合法性**由后端目录判**，前端不自己猜一套枚举；
 *   · 样本载荷解析失败**不许静默吞掉**（拿假结论指挥人比报错更坏）；
 *   · 样本测试**永远**声明「没有产生副作用」（18.3.2 执行引擎尚未交付）。
 */

const schema: AutomationRuleEditorEventSchema = {
  eventType: 'order.created',
  schemaVersion: 1,
  status: 'ACTIVE',
  sourceService: 'trade',
  sourceArtifact: 'trade.order.created',
  schemaDeclared: true,
  note: '',
  fields: [
    {
      path: 'order.amount',
      type: 'NUMBER',
      label: '订单金额',
      required: true,
      enumValues: [],
      allowedOperators: ['GT', 'LT', 'EQ'],
    },
    {
      path: 'order.channel',
      type: 'TEXT',
      label: '订单渠道',
      required: false,
      enumValues: ['AMAZON', 'SHOPIFY'],
      allowedOperators: ['EQ', 'IN'],
    },
  ],
};

const actionCatalog: AutomationRuleEditorActionCatalog = {
  actionTypes: [
    {
      actionType: 'CALL_API',
      label: '调接口',
      description: '调用一个接口目录里真实存在的接口。',
      targetLabel: '接口目录里的接口',
      params: [],
    },
    {
      actionType: 'SEND_NOTIFICATION',
      label: '发通知',
      description: '按模板与渠道发一条通知。',
      targetLabel: '通知对象',
      params: [
        {
          name: 'channel',
          label: '通知渠道',
          type: 'TEXT',
          required: true,
          description: '通知走哪个渠道',
          enumValues: ['EMAIL', 'SMS', 'INAPP'],
        },
      ],
    },
  ],
};

const apiCatalog: AutomationRuleEditorApiCatalog = {
  endpoints: [
    {
      service: 'platform',
      method: 'GET',
      path: '/catalog/spus',
      summary: '商品列表',
      domain: 'catalog',
    },
  ],
  available: true,
  note: '',
};

function condition(overrides: Partial<ConditionDraft> = {}): ConditionDraft {
  return {
    seq: 1,
    fieldPath: 'order.amount',
    operator: 'GT',
    valueText: '',
    valueNumber: 100,
    valueBool: null,
    enabled: true,
    remark: '',
    ...overrides,
  };
}

function action(overrides: Partial<ActionDraft> = {}): ActionDraft {
  return {
    seq: 1,
    actionType: 'CALL_API',
    targetRef: 'platform GET /catalog/spus',
    params: '{}',
    enabled: true,
    remark: '',
    ...overrides,
  };
}

function draft(overrides: Partial<EditorDraft> = {}): EditorDraft {
  return {
    ...emptyDraft(),
    name: '大额订单转人工',
    triggerEventType: 'order.created',
    triggerSchemaVersion: 1,
    conditions: [condition()],
    actions: [action()],
    ...overrides,
  };
}

describe('18.3.1.2 规则编辑器 · 前端模型', () => {
  it('字段 / 操作符 / 必填参数的合法性**由后端目录判**', () => {
    // 字段不在事件字段目录里 ⇒ 明确报错，而不是「看起来合法」。
    const problems = validateDraft(
      draft({ conditions: [condition({ fieldPath: 'order.notExist' })] }),
      { schema, actionCatalog, apiCatalog },
    );
    expect(problems.join('\n')).toContain('不在这个事件的字段目录里');

    // 操作符不适用于该字段 ⇒ 点名可用操作符。
    const badOperator = validateDraft(
      draft({ conditions: [condition({ operator: 'CONTAINS' })] }),
      { schema, actionCatalog, apiCatalog },
    );
    expect(badOperator.join('\n')).toContain('不适用于字段');
    expect(badOperator.join('\n')).toContain('GT');

    // 动作目录里的必填参数没填 ⇒ 点名参数。
    const missingParam = validateDraft(
      draft({
        actions: [action({ actionType: 'SEND_NOTIFICATION', targetRef: '', params: '{}' })],
      }),
      { schema, actionCatalog, apiCatalog },
    );
    expect(missingParam.join('\n')).toContain('缺少必填参数');
    expect(missingParam.join('\n')).toContain('通知渠道');

    // 「调接口」必须给目标接口。
    const missingTarget = validateDraft(draft({ actions: [action({ targetRef: '' })] }), {
      schema,
      actionCatalog,
      apiCatalog,
    });
    expect(missingTarget.join('\n')).toContain('必须指定目标接口');
  });

  it('目录取不到时**不假装校验过**，但仍挡住明显的空缺', () => {
    // 目录不可用 ⇒ 字段合法性不判（不能凭猜报错），但「没选字段」这种空缺照样要报。
    const problems = validateDraft(
      draft({ conditions: [condition({ fieldPath: '', valueNumber: null, valueText: '' })] }),
      {},
    );
    expect(problems.join('\n')).toContain('没选字段');
    expect(problems.join('\n')).not.toContain('不在这个事件的字段目录里');
  });

  it('必填字段的值不能空；非必填字段的值可以空', () => {
    const missingValue = validateDraft(
      draft({ conditions: [condition({ valueNumber: null, valueText: '' })] }),
      { schema, actionCatalog, apiCatalog },
    );
    expect(missingValue.join('\n')).toContain('是必填');

    const optional = validateDraft(
      draft({
        conditions: [condition({ fieldPath: 'order.channel', operator: 'EQ', valueText: '' })],
      }),
      { schema, actionCatalog, apiCatalog },
    );
    expect(optional.join('\n')).not.toContain('是必填');
  });

  it('规则名 / 触发事件 / schema 版本 / 条件 / 动作都不能缺', () => {
    const problems = validateDraft(
      {
        ...emptyDraft(),
        conditions: [],
        actions: [],
      },
      {},
    );
    const text = problems.join('\n');
    expect(text).toContain('规则名不能为空');
    expect(text).toContain('还没选触发事件');
    expect(text).toContain('至少要有一条启用的条件');
    expect(text).toContain('至少要有一条启用的动作');
  });

  it('样本载荷解析失败**不许静默吞掉**', () => {
    const bad = parseSamplePayload('{不是 JSON');
    expect(bad.ok).toBe(false);
    if (!bad.ok) expect(bad.reason).toContain('不是合法 JSON');

    // 数组 / 标量不是「样本对象」。
    expect(parseSamplePayload('[1,2]').ok).toBe(false);
    expect(parseSamplePayload('42').ok).toBe(false);
    expect(parseSamplePayload('"text"').ok).toBe(false);

    // 空载荷是合法的（= 用空样本测）。
    expect(parseSamplePayload('   ').ok).toBe(true);
    const good = parseSamplePayload('{"order":{"amount":120}}');
    expect(good.ok).toBe(true);
    if (good.ok) expect(good.payload).toContain('amount');
  });

  it('动作参数必须是合法 JSON 对象', () => {
    expect(validateParamsJson('')).toBeNull();
    expect(validateParamsJson('{"a":1}')).toBeNull();
    expect(validateParamsJson('{oops')).toContain('不是合法 JSON');
    expect(validateParamsJson('[1]')).toContain('JSON 对象');
  });

  it('样本测试结果**永远**声明「没有产生副作用」', () => {
    const base: AutomationRuleEditorTestResult = {
      testRunId: '11111111-1111-4111-8111-111111111111',
      ruleId: null,
      ruleName: '大额订单转人工',
      draft: true,
      runNo: 1,
      matched: true,
      totalConditions: 2,
      matchedConditions: 2,
      plannedActions: 1,
      sideEffectsApplied: false,
      scopeApplicable: true,
      scopeNote: '',
      conditionEvaluations: [],
      actionPlan: [],
      failureReason: '',
      note: '',
      createdAt: '2026-11-01T10:00:00Z',
    };

    const ok = summarizeTestResult(base);
    expect(ok.headline).toContain('命中');
    expect(ok.notes.join('\n')).toContain('没有产生任何副作用');
    expect(ok.notes.join('\n')).toContain('18.3.2');

    // 未命中时语气是 warn，不是 danger（未命中不等于规则坏了）。
    const missed = summarizeTestResult({ ...base, matched: false, matchedConditions: 0 });
    expect(missed.tone).toBe('warn');
    expect(missed.headline).toContain('未命中');

    // 副作用真发生时必须**报警**，不能照抄「没有产生副作用」。
    const withEffects = summarizeTestResult({ ...base, sideEffectsApplied: true });
    expect(withEffects.notes.join('\n')).toContain('产生了副作用');

    // 失败原因不许被吞掉。
    const failed = summarizeTestResult({ ...base, failureReason: '样本缺少 amount 字段' });
    expect(failed.notes.join('\n')).toContain('样本缺少 amount 字段');
  });

  it('样本测试的序号接着历史往下排', () => {
    expect(nextRunNo([])).toBe(1);
    const rows = [{ runNo: 1 }, { runNo: 4 }, { runNo: 2 }] as AutomationRuleEditorTestRunRow[];
    expect(nextRunNo(rows)).toBe(5);
  });

  it('草稿能编成 18.3.1.1 的登记入参（保存与测试是同一种形状）', () => {
    const input = buildRuleInput(draft({ description: '  ' }));
    expect(input.name).toBe('大额订单转人工');
    expect(input.description).toBeNull();
    expect(input.triggerEventType).toBe('order.created');
    expect(input.conditions?.[0]?.fieldPath).toBe('order.amount');
    expect(input.actions?.[0]?.actionType).toBe('CALL_API');
    expect(input.actions?.[0]?.targetRef).toBe('platform GET /catalog/spus');
    // 条件序号与动作序号都要在（后端按 seq 排序）。
    expect(input.conditions?.[0]?.seq).toBe(1);
    expect(input.actions?.[0]?.seq).toBe(1);

    const testInput = buildTestInput(draft(), '{"order":{"amount":120}}');
    expect(testInput.payload).toContain('amount');
    expect(testInput.rule?.name).toBe('大额订单转人工');
  });

  it('从规则档案回填草稿（改已有规则）', () => {
    const detail: AutomationRuleDetail = {
      rule: {
        id: '22222222-2222-4222-8222-222222222222',
        name: '已有规则',
        description: '说明',
        triggerEventType: 'order.created',
        triggerSchemaVersion: 1,
        priority: 50,
        enabled: false,
        status: 'DISABLED',
        statusLabel: '停用',
        dryRun: false,
        sideEffectMode: 'REAL',
        sideEffectModeLabel: '真实执行',
        consecutiveFailureThreshold: 3,
        conditionCount: 1,
        actionCount: 1,
        scopeCount: 0,
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
        healthLabel: '尚未执行',
        remark: '',
        createdBy: 'u',
        createdAt: '2026-10-01T00:00:00Z',
        updatedAt: '2026-10-01T00:00:00Z',
      },
      conditions: [
        {
          id: 'c1',
          seq: 1,
          fieldPath: 'order.amount',
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
          actionType: 'CALL_API',
          actionTypeLabel: '调接口',
          targetRef: 'platform GET /catalog/spus',
          params: '{}',
          enabled: true,
          remark: '',
        },
      ],
      scopes: [],
      recentExecutions: [],
    };

    const d = draftFromDetail(detail);
    expect(d.id).toBe(detail.rule.id);
    expect(d.name).toBe('已有规则');
    expect(d.enabled).toBe(false);
    expect(d.dryRun).toBe(false);
    expect(d.conditions[0]?.valueNumber).toBe(100);
    expect(d.actions[0]?.targetRef).toBe('platform GET /catalog/spus');
  });

  it('条件的人话描述用字段标签（目录可用时）', () => {
    expect(describeCondition(condition(), schema)).toBe('订单金额 GT 100');
    expect(describeCondition(condition({ fieldPath: '', operator: '' }), null)).toContain(
      '未选字段',
    );
  });

  it('字段目录查询按路径精确匹配', () => {
    expect(fieldByPath(schema, 'order.amount')?.label).toBe('订单金额');
    expect(fieldByPath(schema, 'order.nope')).toBeNull();
    expect(fieldByPath(null, 'order.amount')).toBeNull();
  });
});
