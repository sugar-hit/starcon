import { describe, expect, it } from 'vitest';
import {
  actionSteps,
  buildRerunInput,
  conditionSteps,
  describeDuration,
  describeOutcome,
  firstFailedStep,
  newIdempotencyKey,
  outcomeLabel,
  outcomeTone,
  parseEventPayload,
  stepResultTone,
  summarizePayload,
  summarizeSteps,
} from './automation-run-model';
import type { AutomationRuleRunStepRow } from '../../../../../packages/api-contracts/src/index';

/**
 * 18.3.2.1「执行日志」前端模型的**纯函数**断言。
 *
 * <p>🔴 本文件盯住页面上最容易做错的三件事：
 *
 * <ol>
 *   <li><b>「未执行」不许被说成「成功」</b>：手动重跑在本仓当前没有执行器 ⇒
 *       {@code NOT_ATTEMPTED} 必须显示成「未执行 · 执行器未接通（没有真的跑）」，
 *       绝不能显示成「已重跑成功」。</li>
 *   <li><b>失败要能定位到步</b>：第一处失败的那一步要能被单独拎出来
 *       （失败原因定位 = 从 run 下钻到 step）。</li>
 *   <li><b>耗时全程整数毫秒</b>：前端不碰浮点。</li>
 * </ol>
 */

/** 造一条步骤。 */
function step(overrides: Partial<AutomationRuleRunStepRow>): AutomationRuleRunStepRow {
  return {
    id: 'step-1',
    runId: 'run-1',
    stepNo: 1,
    stepKind: 'CONDITION',
    stepKindLabel: '条件判定',
    conditionId: null,
    conditionSeq: null,
    fieldPath: '',
    operator: '',
    expectedValue: '',
    actualValue: '',
    matched: null,
    actionType: '',
    targetRef: '',
    params: '{}',
    result: 'SUCCEEDED',
    resultLabel: '成功',
    durationMs: 0,
    errorCode: '',
    errorMessage: '',
    sideEffectApplied: false,
    createdAt: '2026-11-02T00:00:00Z',
    ...overrides,
  };
}

describe('结论的语气与文案', () => {
  it('成功是 ok、失败是 danger、未执行是 warn —— 「未执行」不是成功', () => {
    expect(outcomeTone('SUCCEEDED')).toBe('ok');
    expect(outcomeTone('FAILED')).toBe('danger');
    expect(outcomeTone('NOT_ATTEMPTED')).toBe('warn');
    expect(outcomeTone('TIMEOUT')).toBe('warn');
    expect(outcomeTone('SKIPPED')).toBe('muted');
  });

  it('「未执行」的文案必须说清「没有真的跑」', () => {
    expect(outcomeLabel('NOT_ATTEMPTED')).toBe('未执行');
    const text = describeOutcome({
      outcome: 'NOT_ATTEMPTED',
      sideEffectsApplied: false,
      dryRun: false,
    });
    expect(text).toContain('未执行');
    expect(text).toContain('没有真的跑');
    // 🔴 绝不能出现「成功」两个字。
    expect(text).not.toContain('成功');
  });

  it('试运行的执行要说清「不产生真实副作用」', () => {
    const text = describeOutcome({
      outcome: 'SUCCEEDED',
      sideEffectsApplied: false,
      dryRun: true,
    });
    expect(text).toContain('试运行');
    expect(text).toContain('不产生真实副作用');
  });

  it('真实执行且产生副作用时如实说', () => {
    expect(
      describeOutcome({ outcome: 'SUCCEEDED', sideEffectsApplied: true, dryRun: false }),
    ).toContain('已产生副作用');
    expect(
      describeOutcome({ outcome: 'FAILED', sideEffectsApplied: false, dryRun: false }),
    ).toContain('执行失败');
  });
});

describe('耗时（整数毫秒）', () => {
  it('毫秒 / 秒 / 分 / 时都按整数换算，不出现浮点', () => {
    expect(describeDuration(0)).toBe('0 毫秒');
    expect(describeDuration(999)).toBe('999 毫秒');
    expect(describeDuration(1000)).toBe('1 秒');
    expect(describeDuration(1500)).toBe('1 秒');
    expect(describeDuration(65_000)).toBe('1 分 5 秒');
    expect(describeDuration(120_000)).toBe('2 分');
    expect(describeDuration(3_600_000)).toBe('1 小时');
    expect(describeDuration(3_900_000)).toBe('1 小时 5 分');
  });

  it('负数与非数字都显示成「—」，不显示 NaN', () => {
    expect(describeDuration(-1)).toBe('—');
    expect(describeDuration(Number.NaN)).toBe('—');
  });
});

describe('失败原因定位', () => {
  it('第一处失败的那一步能被单独拎出来', () => {
    const steps = [
      step({ stepNo: 1, result: 'SUCCEEDED', matched: true }),
      step({
        stepNo: 2,
        stepKind: 'ACTION',
        actionType: 'UPDATE_STATUS',
        result: 'FAILED',
        errorCode: 'HTTP_500',
        errorMessage: '改价接口返回 500',
      }),
      step({ stepNo: 3, stepKind: 'ACTION', actionType: 'SEND_NOTIFICATION', result: 'SUCCEEDED' }),
    ];
    const failed = firstFailedStep(steps);
    expect(failed).not.toBeNull();
    expect(failed?.stepNo).toBe(2);
    expect(failed?.errorCode).toBe('HTTP_500');
    expect(failed?.errorMessage).toBe('改价接口返回 500');
  });

  it('没有失败步时返回 null（不编造一个失败）', () => {
    expect(firstFailedStep([step({ result: 'SUCCEEDED' })])).toBeNull();
    expect(firstFailedStep([])).toBeNull();
  });

  it('条件判定与动作执行能分开数', () => {
    const steps = [
      step({ stepNo: 1, stepKind: 'CONDITION', matched: true, result: 'SUCCEEDED' }),
      step({ stepNo: 2, stepKind: 'CONDITION', matched: false, result: 'NOT_MATCHED' }),
      step({ stepNo: 3, stepKind: 'ACTION', actionType: 'CREATE_TASK', result: 'SUCCEEDED' }),
    ];
    expect(conditionSteps(steps)).toHaveLength(2);
    expect(actionSteps(steps)).toHaveLength(1);
    const summary = summarizeSteps(steps);
    expect(summary).toEqual({
      total: 3,
      succeeded: 2,
      failed: 0,
      notMatched: 1,
      notAttempted: 0,
    });
  });

  it('步骤结果的语气分得开', () => {
    expect(stepResultTone('SUCCEEDED')).toBe('ok');
    expect(stepResultTone('FAILED')).toBe('danger');
    expect(stepResultTone('NOT_MATCHED')).toBe('muted');
    expect(stepResultTone('NOT_ATTEMPTED')).toBe('warn');
  });
});

describe('触发上下文解析', () => {
  it('合法 JSON 对象原样通过', () => {
    const result = parseEventPayload('{"orderNo":"SO-1"}');
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.value).toBe('{"orderNo":"SO-1"}');
  });

  it('空串当成「没有上下文」，不是错误', () => {
    const result = parseEventPayload('   ');
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.value).toBe('');
  });

  it('🔴 解析不了就点名报错，不伪装成「上下文为空」', () => {
    const result = parseEventPayload('{not json');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toContain('合法的 JSON');
  });

  it('数组与标量都不是合法上下文', () => {
    expect(parseEventPayload('[1,2]').ok).toBe(false);
    expect(parseEventPayload('"hello"').ok).toBe(false);
    expect(parseEventPayload('42').ok).toBe(false);
  });

  it('摘要能摊平成一行，空上下文有专门的说法', () => {
    expect(summarizePayload('{"orderNo":"SO-1","amount":100}')).toBe('orderNo=SO-1  ·  amount=100');
    expect(summarizePayload('{}')).toBe('（无上下文）');
    expect(summarizePayload('')).toBe('（无上下文）');
    // 摊不平就原样给，不假装解析成功。
    expect(summarizePayload('{broken')).toBe('{broken');
  });
});

describe('重跑幂等键', () => {
  it('每次生成的都是非空字符串', () => {
    const key = newIdempotencyKey();
    expect(key.length).toBeGreaterThan(0);
    expect(key.startsWith('rerun-')).toBe(true);
  });

  it('两次生成的不一样（否则两次重跑会被误判成重复）', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });

  it('入参把理由与幂等键一起带上', () => {
    const input = buildRerunInput('  价格改错了  ', 'rerun-key-1');
    expect(input).toEqual({ reason: '价格改错了', idempotencyKey: 'rerun-key-1' });
  });
});
