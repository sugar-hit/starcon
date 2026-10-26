import { describe, expect, it } from 'vitest';
import type {
  TextCheckRow,
  TextConfidence,
  TextTaskDetail,
  TextTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  dimensionLabel,
  dimensionTone,
  draftOriginLabel,
  publishGate,
  sortChecks,
  statusTone,
  verdictLabel,
  verdictTone,
} from './text-translation-model';

/**
 * 18.4.2.1 文本与翻译 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **「不适用」不能显示成 0%** —— 没有术语可保护的任务，术语覆盖率**没有信息量**，
 *    显示成 0% 是在惩罚一件本来就不该扣分的事。
 * 2. **SKIPPED 是「没查」，不是「通过」** —— 显示成绿色「通过」等于骗人。
 * 3. **发布闸有两道** —— 没人工确认 ⇒ 不能发；有 FAIL 的检查结论 ⇒ 也不能发。
 *    少一道，蓝色框就只剩一半。
 * 4. **检查结论失败在前** —— 失败的那条不能被埋在一堆绿色里。
 */

const task = (over: Partial<TextTaskRow>): TextTaskRow => ({
  id: '00000000-0000-4000-8000-000000000001',
  taskNo: 'TX-ABCD1234-000001',
  contentType: 'DESCRIPTION',
  contentTypeLabel: '描述',
  sourceText: 'hello',
  sourceLocale: 'en-US',
  targetLocale: 'de',
  styleRequirement: '',
  upstreamRefType: '',
  upstreamRefId: '',
  status: 'DRAFTED',
  statusLabel: '待复核',
  currentDraftId: '00000000-0000-4000-8000-000000000002',
  currentDraftText: 'hallo',
  confidence: 1,
  confidenceTermCoverage: 1,
  confidencePlaceholderIntegrity: 1,
  confidenceCheckPassRate: 1,
  confidenceLengthCompliance: 1,
  checkTotal: 0,
  checkFailed: 0,
  checkWarned: 0,
  confirmedBy: '',
  confirmedAt: '',
  publishedAt: '',
  rejectReason: '',
  createdAt: '2026-10-25T00:00:00Z',
  updatedAt: '2026-10-25T00:00:00Z',
  ...over,
});

const check = (over: Partial<TextCheckRow>): TextCheckRow => ({
  id: '00000000-0000-4000-8000-000000000003',
  taskId: '00000000-0000-4000-8000-000000000001',
  draftId: '00000000-0000-4000-8000-000000000002',
  ruleCode: 'TERMINOLOGY',
  ruleLabel: '术语保护',
  verdict: 'PASS',
  matchedItem: '',
  detail: '',
  createdAt: '2026-10-25T00:00:00Z',
  ...over,
});

const detail = (over: Partial<TextTaskDetail>): TextTaskDetail => ({
  task: task({}),
  draft: null as unknown as TextTaskDetail['draft'],
  history: [],
  checks: [],
  violations: [],
  confidence: null as unknown as TextTaskDetail['confidence'],
  ...over,
});

const confidence = (over: Partial<TextConfidence>): TextConfidence => ({
  total: 0.9,
  termCoverage: 1,
  placeholderIntegrity: 1,
  checkPassRate: 1,
  lengthCompliance: 1,
  explanation: [],
  ...over,
});

describe('18.4.2.1 文本与翻译 · 一维置信度的展示口径', () => {
  it('null 是「不适用」，不是 0%', () => {
    expect(dimensionLabel(null)).toBe('不适用');
    expect(dimensionLabel(null)).not.toContain('0%');
    expect(dimensionTone(null)).toBe('muted');
  });

  it('undefined 也是「不适用」', () => {
    expect(dimensionLabel(undefined)).toBe('不适用');
    expect(dimensionTone(undefined)).toBe('muted');
  });

  it('0 与 null 是两件不同的事：0 是红色，null 是灰色', () => {
    expect(dimensionLabel(0)).toBe('0%');
    expect(dimensionTone(0)).toBe('danger');
    expect(dimensionTone(null)).not.toBe(dimensionTone(0));
  });

  it('分档：高绿、中黄、低红', () => {
    expect(dimensionTone(1)).toBe('ok');
    expect(dimensionTone(0.8)).toBe('ok');
    expect(dimensionTone(0.6)).toBe('warn');
    expect(dimensionTone(0.2)).toBe('danger');
  });
});

describe('18.4.2.1 文本与翻译 · 检查结论的判定', () => {
  it('PASS / WARN / FAIL / SKIPPED 四档分得开', () => {
    expect(verdictTone('PASS')).toBe('ok');
    expect(verdictTone('WARN')).toBe('warn');
    expect(verdictTone('FAIL')).toBe('danger');
    // 🔴 SKIPPED 是「没查」，不是「通过」—— 绿色会骗人。
    expect(verdictTone('SKIPPED')).toBe('muted');
    expect(verdictTone('SKIPPED')).not.toBe('ok');
  });

  it('中文说法分开', () => {
    expect(verdictLabel('SKIPPED')).toBe('不适用');
    expect(verdictLabel('FAIL')).toBe('失败');
    expect(verdictLabel('WARN')).toBe('警告');
    expect(verdictLabel('PASS')).toBe('通过');
  });
});

describe('18.4.2.1 文本与翻译 · 发布闸（两道）', () => {
  it('没有译文时不能发', () => {
    const gate = publishGate(null);
    expect(gate.allowed).toBe(false);
    expect(gate.reason).toContain('还没有选中');
  });

  it('没人工确认 ⇒ 不能发', () => {
    const gate = publishGate(detail({ task: task({ status: 'DRAFTED' }) }));
    expect(gate.allowed).toBe(false);
    expect(gate.reason).toContain('人工确认');
  });

  it('已确认但有 FAIL 的检查结论 ⇒ 仍然不能发', () => {
    const gate = publishGate(
      detail({
        task: task({ status: 'CONFIRMED' }),
        checks: [check({ verdict: 'FAIL', detail: '品牌词缺失' })],
      }),
    );
    expect(gate.allowed).toBe(false);
    expect(gate.reason).toContain('1');
  });

  it('已确认且无 FAIL ⇒ 可以发', () => {
    const gate = publishGate(
      detail({
        task: task({ status: 'CONFIRMED' }),
        checks: [check({ verdict: 'PASS' }), check({ verdict: 'WARN' })],
      }),
    );
    expect(gate.allowed).toBe(true);
  });

  it('已发布不能再发一次', () => {
    const gate = publishGate(detail({ task: task({ status: 'PUBLISHED' }) }));
    expect(gate.allowed).toBe(false);
    expect(gate.reason).toContain('已经发布');
  });

  it('已驳回不能再发', () => {
    const gate = publishGate(detail({ task: task({ status: 'REJECTED' }) }));
    expect(gate.allowed).toBe(false);
    expect(gate.reason).toContain('驳回');
  });

  it('WARN 单独存在时不拦发布（那是人的判断，不是硬伤）', () => {
    const gate = publishGate(
      detail({ task: task({ status: 'CONFIRMED' }), checks: [check({ verdict: 'WARN' })] }),
    );
    expect(gate.allowed).toBe(true);
  });
});

describe('18.4.2.1 文本与翻译 · 检查结论的排序', () => {
  it('失败在最前，其次警告，不适用，再次通过', () => {
    const sorted = sortChecks([
      check({ id: '1', verdict: 'PASS' }),
      check({ id: '2', verdict: 'SKIPPED' }),
      check({ id: '3', verdict: 'FAIL' }),
      check({ id: '4', verdict: 'WARN' }),
    ]);
    expect(sorted.map((c) => c.verdict)).toEqual(['FAIL', 'WARN', 'SKIPPED', 'PASS']);
  });

  it('排序不改动原数组', () => {
    const original = [check({ id: '1', verdict: 'PASS' }), check({ id: '2', verdict: 'FAIL' })];
    sortChecks(original);
    expect(original.map((c) => c.verdict)).toEqual(['PASS', 'FAIL']);
  });
});

describe('18.4.2.1 文本与翻译 · 任务状态与草稿来源', () => {
  it('状态语气分得开', () => {
    expect(statusTone('PUBLISHED')).toBe('ok');
    expect(statusTone('REJECTED')).toBe('danger');
    expect(statusTone('CONFIRMED')).toBe('warn');
    expect(statusTone('DRAFTED')).toBe('muted');
    expect(statusTone('PENDING')).toBe('muted');
  });

  it('草稿来源：人工录入与 AI 生成分开说', () => {
    expect(draftOriginLabel('MANUAL')).toBe('人工录入');
    expect(draftOriginLabel('AI')).toBe('AI 生成');
  });
});
