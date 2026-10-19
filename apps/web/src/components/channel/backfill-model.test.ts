import { describe, expect, it } from 'vitest';
import {
  RESOURCE_TYPE_LABELS,
  STATUS_LABELS,
  TRIGGER_LABELS,
  buildTaskInput,
  countHint,
  delayedDiscoveryHint,
  derivedIdempotencyKey,
  emptyBackfillForm,
  statusTone,
  validateWindow,
  type BackfillForm,
} from './backfill-model';

/**
 * 02.6.2.1 增量补拉的**纯函数**行为约束。
 *
 * <p>这三条是页面上最容易写错、且错了会直接造成资损的地方：
 * 时间窗边界（错了就是漏补或重补整整一段）、幂等键（错了就是重复发起补拉）、
 * 以及「补拉可能延迟发现」的标记（错了就是让人把不完整补拉当成完整补拉验收）。
 */

const base: BackfillForm = {
  ...emptyBackfillForm(),
  connectionId: 'conn-1',
  resourceType: 'ORDER',
  triggerSource: 'MANUAL',
  windowStart: '2026-03-14T10:00',
  windowEnd: '2026-03-14T12:00',
  paginationMode: 'TIME_INCREMENTAL',
  idempotencyKey: '',
  pageSize: 100,
  maxPages: 20,
};

describe('补拉时间窗', () => {
  it('右端晚于左端才算合法', () => {
    expect(validateWindow(base)).toBeNull();
    expect(validateWindow({ ...base, windowEnd: '2026-03-14T10:00' })).toContain('右端必须晚于左端');
    expect(validateWindow({ ...base, windowEnd: '2026-03-14T09:00' })).toContain('右端必须晚于左端');
  });

  it('零长窗口被拒：捞不到任何东西却会让人以为该区间无缺口', () => {
    expect(validateWindow({ ...base, windowEnd: base.windowStart })).not.toBeNull();
  });

  it('缺端点与延伸到未来都被拒', () => {
    expect(validateWindow({ ...base, windowStart: '' })).toContain('时间窗两端都要填');
    const far = new Date(Date.now() + 30 * 24 * 3600_000).toISOString().slice(0, 16);
    expect(validateWindow({ ...base, windowEnd: far })).toContain('不能延伸到未来');
  });

  it('入参按 ISO 提交，端点时刻不被本地时区改写', () => {
    const input = buildTaskInput(base);
    expect(input.windowStart).toBe(new Date('2026-03-14T10:00').toISOString());
    expect(input.windowEnd).toBe(new Date('2026-03-14T12:00').toISOString());
    expect(new Date(input.windowEnd).getTime()).toBeGreaterThan(new Date(input.windowStart).getTime());
  });
});

describe('幂等键', () => {
  it('按「连接 + 资源类型 + 窗口左端」派生：同一段区间重复发起拿回同一个键', () => {
    const first = derivedIdempotencyKey(base);
    const same = derivedIdempotencyKey({ ...base, triggerSource: 'WEBHOOK_GAP' });
    expect(same).toBe(first);

    // 换了资源类型或换了窗口，就是另一段区间，键必须不同
    expect(derivedIdempotencyKey({ ...base, resourceType: 'LISTING' })).not.toBe(first);
    expect(derivedIdempotencyKey({ ...base, windowStart: '2026-03-14T11:00' })).not.toBe(first);
  });

  it('显式填写的键优先于派生键', () => {
    expect(buildTaskInput({ ...base, idempotencyKey: 'gap-detect:manual' }).idempotencyKey).toBe(
      'gap-detect:manual',
    );
    expect(buildTaskInput(base).idempotencyKey).toBe(derivedIdempotencyKey(base));
  });

  it('没选连接时派生键不会塌成同一个值', () => {
    expect(derivedIdempotencyKey({ ...base, connectionId: '' })).toContain('no-connection');
  });
});

describe('平台只给「最近 N 条」时的标记', () => {
  it('选了 RECENT_N_SCAN 就必须置位「补拉可能延迟发现」', () => {
    const input = buildTaskInput({ ...base, paginationMode: 'RECENT_N_SCAN' });
    expect(input.paginationMode).toBe('RECENT_N_SCAN');
    expect(input.delayedDiscovery).toBe(true);
  });

  it('按时间增量的平台不需要这个标记', () => {
    expect(buildTaskInput(base).delayedDiscovery).toBe(false);
  });

  it('提示文案说清「为什么」而不只是「有风险」', () => {
    const hint = delayedDiscoveryHint();
    expect(hint).toContain('最近 N 条');
    expect(hint).toContain('延迟发现');
  });
});

describe('展示口径', () => {
  it('四个数字分开呈现，不合成一个总数', () => {
    const hint = countHint({
      fetchedCount: 10,
      createdCount: 3,
      updatedCount: 2,
      deduplicatedCount: 4,
      failedCount: 1,
    } as never);
    expect(hint).toContain('拉取 10');
    expect(hint).toContain('新增 3');
    expect(hint).toContain('更新 2');
    expect(hint).toContain('去重 4');
    expect(hint).toContain('失败 1');
  });

  it('失败用危险色，部分完成用中性提醒色（部分完成不是事故）', () => {
    expect(statusTone('FAILED')).toBe('danger');
    expect(statusTone('PARTIAL')).toBe('warning');
    expect(statusTone('SUCCEEDED')).toBe('success');
  });

  it('枚举词表覆盖后端的全部取值', () => {
    expect(Object.keys(RESOURCE_TYPE_LABELS)).toEqual([
      'ORDER',
      'LISTING',
      'INVENTORY',
      'SETTLEMENT',
      'PRODUCT',
    ]);
    expect(Object.keys(TRIGGER_LABELS)).toEqual([
      'WEBHOOK_GAP',
      'CURSOR_REWIND',
      'MANUAL',
      'PLATFORM_RECOVERY',
    ]);
    expect(Object.keys(STATUS_LABELS)).toEqual([
      'PENDING',
      'RUNNING',
      'SUCCEEDED',
      'PARTIAL',
      'FAILED',
      'CANCELLED',
    ]);
  });
});
