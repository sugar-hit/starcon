import { describe, expect, it } from 'vitest';
import {
  attemptOutcomeLabel,
  canRetryTask,
  capabilityUnavailableMessage,
  durationLabel,
  emptyStateMessage,
  errorLine,
  errorScopeLabel,
  groupTimelineByPhase,
  retryOutcomeLine,
  retryableSteps,
  stageLabel,
  stageTone,
  stepScopeLabel,
  stepStateLabel,
  stepStateTone,
  taskKindBadge,
  taskKindLabel,
  taskLabel,
  taskRetryHint,
  timeLabel,
  unavailableDimensions,
  type ListingPublishTaskCapabilities,
  type ListingPublishTaskError,
  type ListingPublishTaskPhase,
  type ListingPublishTaskStepRow,
} from './listing-publish-task-model';

/**
 * 4.3.1.3 发布任务 前端模型的纯函数断言。
 *
 * 🔴 这里钉住的是**页面上最容易做丢的几件事**：
 *   1. 「审核中」不是「已发布」；
 *   2. 重试成功 = 已重新提交平台，**不是**「已发布」；
 *   3. 两条上游的来源标签分得开，原始状态不丢；
 *   4. 不可重试的步骤也要有理由（不给点了没反应的按钮）；
 *   5. 蓝图六阶段里没有独立步骤行的阶段（平台创建 / 变体创建）如实显示，
 *      **不凭空造两个步骤**；
 *   6. 空态分三种（没有任务 / 没有时间线 / 没有错误），能力未交付的提示与「没有数据」分开。
 */

function step(overrides: Partial<ListingPublishTaskStepRow> = {}): ListingPublishTaskStepRow {
  return {
    stepId: 'step-1',
    taskId: 'task-1',
    jobId: 'job-1',
    targetId: 'target-1',
    stepKey: 'SUBMIT',
    phase: '提交审核',
    scope: 'TARGET',
    position: 4,
    state: 'FAILED',
    detail: '平台提交失败：图片尺寸超限',
    retryable: true,
    retryBlockedReason: '',
    startedAt: '2026-03-04T05:06:07.000+08:00',
    finishedAt: '2026-03-04T05:06:09.000+08:00',
    durationMillis: 2000,
    attemptCount: 0,
    attempts: [],
    ...overrides,
  };
}

function error(overrides: Partial<ListingPublishTaskError> = {}): ListingPublishTaskError {
  return {
    scope: 'STEP',
    stepKey: 'SUBMIT',
    phase: '提交审核',
    reason: '平台提交失败：图片尺寸超限',
    occurredAt: '2026-03-04T05:06:09.000+08:00',
    retryable: true,
    ...overrides,
  };
}

const PHASES: ListingPublishTaskPhase[] = [
  { phase: '内容校验', description: '', stepKeys: ['TEMPLATE', 'CONTENT'], note: '' },
  { phase: '平台创建', description: '', stepKeys: [], note: '4.3.1.1 没有为这一段单独记步骤行' },
  { phase: '变体创建', description: '', stepKeys: [], note: '4.3.1.1 没有为这一段单独记步骤行' },
  { phase: '媒体上传', description: '', stepKeys: ['MEDIA'], note: '' },
  { phase: '价格库存设置', description: '', stepKeys: ['PRICE', 'STOCK'], note: '' },
  { phase: '提交审核', description: '', stepKeys: ['SUBMIT'], note: '' },
];

describe('阶段文案：🔴「审核中」不是「已发布」', () => {
  it('IN_REVIEW 显示为「审核中（等平台回执）」，绝不说成已发布', () => {
    expect(stageLabel('IN_REVIEW')).toContain('审核中');
    expect(stageLabel('IN_REVIEW')).not.toContain('已发布');
    expect(stageLabel('SUCCEEDED')).toBe('已成功');
  });

  it('阶段色调分得开', () => {
    expect(stageTone('SUCCEEDED')).toBe('success');
    expect(stageTone('IN_REVIEW')).toBe('info');
    expect(stageTone('FAILED')).toBe('danger');
    expect(stageTone('REJECTED')).toBe('warning');
    expect(stageTone('PENDING')).toBe('neutral');
  });

  it('未知阶段原样返回，不编一个文案', () => {
    expect(stageLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('步骤状态文案', () => {
  it('六个状态各有各的文案', () => {
    expect(stepStateLabel('PENDING')).toBe('未开始');
    expect(stepStateLabel('RUNNING')).toBe('进行中');
    expect(stepStateLabel('SUCCEEDED')).toBe('成功');
    expect(stepStateLabel('FAILED')).toBe('失败');
    expect(stepStateLabel('SKIPPED')).toBe('已跳过');
    expect(stepStateLabel('BLOCKED')).toBe('被挡住');
  });

  it('色调分得开', () => {
    expect(stepStateTone('SUCCEEDED')).toBe('success');
    expect(stepStateTone('RUNNING')).toBe('info');
    expect(stepStateTone('FAILED')).toBe('danger');
    expect(stepStateTone('BLOCKED')).toBe('warning');
    expect(stepStateTone('SKIPPED')).toBe('neutral');
  });
});

describe('🔴 两条上游的来源与状态不揉', () => {
  it('来源标签分得开', () => {
    expect(taskKindLabel('ONECLICK')).toContain('4.3.1.1');
    expect(taskKindLabel('BATCH')).toContain('4.3.1.2');
    expect(taskKindBadge('ONECLICK')).toBe('一键刊登');
    expect(taskKindBadge('BATCH')).toBe('批量');
  });

  it('作业级 / 目标级 / 任务级三种归属各有标签', () => {
    expect(stepScopeLabel('JOB')).toBe('作业级');
    expect(stepScopeLabel('TARGET')).toBe('目标级');
    expect(stepScopeLabel('TASK')).toBe('任务级');
  });

  it('展示名把平台 / 店铺 / 站点 / 语言拼起来', () => {
    expect(
      taskLabel({
        platformCode: 'AMAZON',
        shopCode: 'US-1',
        marketplaceCode: 'US',
        locale: 'en-US',
      }),
    ).toBe('AMAZON / US-1 / US（en-US）');
  });

  it('没有站点维度的任务（批量发布）也能拼出可读名', () => {
    expect(
      taskLabel({
        platformCode: 'AMAZON',
        shopCode: 'US-1',
        marketplaceCode: null,
        locale: null,
      }),
    ).toBe('AMAZON / US-1');
  });
});

describe('🔴 重试语义', () => {
  it('重试成功只说「已重新提交平台」，不说已发布', () => {
    const line = retryOutcomeLine({
      outcome: 'SUCCEEDED',
      reason: '已重新提交平台审核',
      attemptNo: 2,
    });
    expect(line).toContain('第 2 次');
    expect(line).toContain('已提交平台');
    expect(line).not.toContain('已发布');
  });

  it('重试没成要说清原因', () => {
    const line = retryOutcomeLine({ outcome: 'FAILED', reason: '平台已驳回', attemptNo: 1 });
    expect(line).toContain('没成');
    expect(line).toContain('平台已驳回');
  });

  it('尝试结果文案分得开', () => {
    expect(attemptOutcomeLabel('SUCCEEDED')).toBe('已重新提交平台');
    expect(attemptOutcomeLabel('FAILED')).toBe('重试没成');
  });

  it('只有失败的任务能整体重试', () => {
    expect(canRetryTask({ state: 'FAILED' })).toBe(true);
    expect(canRetryTask({ state: 'REJECTED' })).toBe(false);
    expect(canRetryTask({ state: 'SUBMITTED' })).toBe(false);
    expect(canRetryTask({ state: 'DRAFT' })).toBe(false);
  });

  it('被驳回的任务给出「重发没有意义」的理由', () => {
    expect(taskRetryHint({ state: 'REJECTED' })).toContain('驳回');
    expect(taskRetryHint({ state: 'FAILED' })).toContain('同一份');
    expect(taskRetryHint({ state: 'DRAFT' })).toContain('只有失败');
  });

  it('可重试的步骤被挑出来（只有后端说可以的那些）', () => {
    const steps = [
      step({ stepKey: 'MEDIA', retryable: true }),
      step({ stepKey: 'CONTENT', retryable: false, retryBlockedReason: '内容快照已冻结' }),
      step({ stepKey: 'SUBMIT', retryable: true }),
    ];
    expect(retryableSteps(steps).map((s) => s.stepKey)).toEqual(['MEDIA', 'SUBMIT']);
  });
});

describe('🔴 蓝图六阶段：没有独立步骤行的阶段如实显示', () => {
  it('按阶段分组，且「平台创建」「变体创建」带着 note 出现', () => {
    const timeline = [
      step({ stepKey: 'TEMPLATE', phase: '内容校验' }),
      step({ stepKey: 'CONTENT', phase: '内容校验' }),
      step({ stepKey: 'MEDIA', phase: '媒体上传' }),
      step({ stepKey: 'SUBMIT', phase: '提交审核' }),
    ];
    const groups = groupTimelineByPhase(timeline, PHASES);
    const names = groups.map((g) => g.phase);
    // 🔴 「平台创建」「变体创建」**在**列表里（它们标出蓝图与上游记录的真实缺口）；
    //    「价格库存设置」既没有步骤也没有 note ⇒ 纯噪音，不显示。
    expect(names).toEqual(['内容校验', '平台创建', '变体创建', '媒体上传', '提交审核']);
    const platformCreate = groups.find((g) => g.phase === '平台创建');
    expect(platformCreate?.steps).toEqual([]);
    expect(platformCreate?.note).toContain('4.3.1.1');
  });

  it('分组里的步骤按原顺序保留', () => {
    const timeline = [
      step({ stepKey: 'TEMPLATE', phase: '内容校验' }),
      step({ stepKey: 'CONTENT', phase: '内容校验' }),
    ];
    const groups = groupTimelineByPhase(timeline, PHASES);
    expect(groups[0]?.steps.map((s) => s.stepKey)).toEqual(['TEMPLATE', 'CONTENT']);
  });

  it('后端出现了未登记的阶段时也不静默丢步骤', () => {
    const timeline = [step({ stepKey: 'WEIRD', phase: '未登记阶段' })];
    const groups = groupTimelineByPhase(timeline, PHASES);
    expect(groups.some((g) => g.phase === '未登记阶段')).toBe(true);
  });
});

describe('错误明细', () => {
  it('三个来源层级各有标签', () => {
    expect(errorScopeLabel('TASK')).toBe('任务');
    expect(errorScopeLabel('STEP')).toBe('步骤');
    expect(errorScopeLabel('ATTEMPT')).toBe('重试');
  });

  it('一行文案说清是哪儿出的错', () => {
    expect(errorLine(error())).toContain('步骤 SUBMIT');
    expect(errorLine(error())).toContain('图片尺寸超限');
    expect(errorLine(error({ scope: 'TASK', stepKey: null, phase: null }))).toContain('任务');
  });
});

describe('耗时与时间', () => {
  it('耗时按量级换算', () => {
    expect(durationLabel(null)).toBe('');
    expect(durationLabel(500)).toBe('500 毫秒');
    expect(durationLabel(2000)).toBe('2 秒');
    expect(durationLabel(65000)).toBe('1 分 5 秒');
    expect(durationLabel(120000)).toBe('2 分钟');
  });

  it('时间戳能格式化，坏值原样返回', () => {
    expect(timeLabel(null)).toBe('');
    expect(timeLabel('不是时间')).toBe('不是时间');
    expect(timeLabel('2026-03-04T05:06:07.000+08:00')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });
});

describe('空态与能力声明', () => {
  it('三种空态文案不同', () => {
    const messages = [
      emptyStateMessage('tasks'),
      emptyStateMessage('timeline'),
      emptyStateMessage('errors'),
    ];
    expect(new Set(messages).size).toBe(3);
  });

  it('「没有数据」与「能力未交付」的提示不是同一句', () => {
    expect(capabilityUnavailableMessage('platform_create_and_variant_phases')).toContain('4.3.1.1');
    expect(capabilityUnavailableMessage('price_source_retry')).toContain('5.1.1.1');
    expect(capabilityUnavailableMessage('batch_task_retry')).toContain('4.3.1.2');
    expect(capabilityUnavailableMessage('别的')).toBe('该能力尚未交付。');
  });

  it('未交付的维度被挑出来', () => {
    const caps = {
      dimensions: [
        { dimension: 'a', description: '', available: true, source: '', ownerModule: '', impact: '' },
        { dimension: 'b', description: '', available: false, source: '', ownerModule: '5.1.1.1', impact: '' },
      ],
    } as ListingPublishTaskCapabilities;
    expect(unavailableDimensions(caps).map((d) => d.dimension)).toEqual(['b']);
  });
});
