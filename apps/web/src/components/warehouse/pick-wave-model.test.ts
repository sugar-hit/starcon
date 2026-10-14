import { describe, expect, it } from 'vitest';
import type { PickWaveRuleRow } from '../../../../../packages/api-contracts/src/index';
import {
  canCancel,
  canComplete,
  canDetach,
  canRelease,
  eventTypeLabel,
  groupVerdict,
  memberWarnings,
  mergeKeyText,
  pickWaveQuery,
  progressText,
  qtyText,
  ruleScopeText,
  slotText,
  statusLabel,
  statusTone,
  waveHint,
} from './pick-wave-model';

/**
 * 8.3.2.2 波次前端模型的展示口径。
 *
 * <p>这些断言钉的都是「界面上最容易说错、而静态检查一条都查不出来」的地方：
 * 「现在能并」与「得等上一波」、「跨区」与「漏配规则」、「没量过」与「量过了是 0」，
 * 以及**合并不改订单粒度**这句话有没有真的出现在完成后的提示里。
 * 写错它们不会让 typecheck 红，只会让看板上少了几张单而没人知道为什么。
 */

const rule = (over: Partial<PickWaveRuleRow>): PickWaveRuleRow =>
  ({
    ruleCode: 'R-1',
    ruleName: '规则',
    carrierMatch: 'SF',
    carrierUnbounded: false,
    zoneId: null,
    zoneCode: null,
    zoneUnbounded: true,
    timeSlotStart: 9,
    timeSlotEnd: 18,
    slotLabel: '09:00-18:00（仓本地）',
    maxOrderCount: 10,
    ...over,
  }) as PickWaveRuleRow;

describe('状态与按钮可用性', () => {
  it('四态里没有「已发货」——波次结束不等于货可以发', () => {
    expect(statusLabel('PLANNED')).toBe('已排波');
    expect(statusLabel('PICKING')).toBe('拣货中');
    expect(statusLabel('COMPLETED')).toBe('已完成');
    expect(statusLabel('CANCELLED')).toBe('已取消');
    expect(statusTone('CANCELLED')).not.toBe(statusTone('COMPLETED'));
  });

  it('只有「已排波」能放行与移出成员；放行之后不能再抽走已发出的单', () => {
    expect(canRelease('PLANNED')).toBe(true);
    expect(canRelease('PICKING')).toBe(false);
    expect(canDetach('PLANNED', 3)).toBe(true);
    expect(canDetach('PICKING', 3)).toBe(false);
    // 只剩一个成员时不允许再移：空波次既不能放行也不能完成。
    expect(canDetach('PLANNED', 1)).toBe(false);
  });

  it('取消只对未完态有意义', () => {
    expect(canCancel('PLANNED')).toBe(true);
    expect(canCancel('PICKING')).toBe(true);
    expect(canCancel('COMPLETED')).toBe(false);
    expect(canCancel('CANCELLED')).toBe(false);
  });

  it('完成波次的判据是「全部成员工单终态」而不是「拣了多少件」', () => {
    // 3 张里有 2 张到终态：拣了很多但不能完成。
    expect(canComplete('PICKING', 2, 3)).toBe(false);
    expect(canComplete('PICKING', 3, 3)).toBe(true);
    // 一个成员都没有的波次不能「完成」（后端守卫也会拒）。
    expect(canComplete('PICKING', 0, 0)).toBe(false);
    // 没放行就不能完成。
    expect(canComplete('PLANNED', 3, 3)).toBe(false);
  });
});

describe('合并口径文案', () => {
  it('库区为空时必须写成「跨区或库区不限」而不是留空', () => {
    // 空格在这张表上有两种完全不同的含义（跨区 / 不限），都区别于「有个叫空的区」。
    expect(mergeKeyText('SF', null)).toBe('SF / 跨区或库区不限');
    expect(mergeKeyText('SF', 'A')).toBe('SF / A');
    // 承运渠道不限也要写出来，否则像「没填」。
    expect(mergeKeyText('', 'A')).toBe('承运渠道不限 / A');
  });

  it('规则的覆盖范围把「不限」显式写出来', () => {
    expect(ruleScopeText(rule({ carrierUnbounded: true, zoneUnbounded: true }))).toBe(
      '承运渠道不限 / 库区不限 / 09:00-18:00（仓本地）',
    );
    const scoped = rule({ zoneId: 'z1' as never, zoneCode: 'A', zoneUnbounded: false });
    expect(ruleScopeText(scoped)).toBe('SF / 库区 A / 09:00-18:00（仓本地）');
  });

  it('时段按两位小时渲染（0 必须补零，否则「00」与「0」看起来像两种排班）', () => {
    expect(slotText(9, 18)).toBe('09:00-18:00');
    expect(slotText(0, 24)).toBe('00:00-24:00');
  });

  it('流水的六种事件都有中文名', () => {
    expect(eventTypeLabel('PLANNED')).toBe('排波');
    expect(eventTypeLabel('TASK_DETACHED')).toBe('移出工单');
    expect(eventTypeLabel('RELEASED')).toBe('放行');
  });
});

describe('候选组结论', () => {
  it('已有同键未完波次时，报「等它收工」并说出是哪一波', () => {
    const text = groupVerdict({
      openWaveId: 'w1',
      openWaveCode: 'PW-1',
      eligibleCount: 0,
      waitingCount: 2,
      unruledCount: 0,
    });
    expect(text).toContain('PW-1');
    expect(text).toContain('2 张在等');
    // 关键：不能显示成「可并入 0 张」——那会让人以为规则没生效。
    expect(text).not.toContain('可并入');
  });

  it('没有规则认领时指向「去配规则」，而不是报成 0 张', () => {
    const text = groupVerdict({
      openWaveId: null,
      openWaveCode: null,
      eligibleCount: 0,
      waitingCount: 0,
      unruledCount: 3,
    });
    expect(text).toContain('3 张');
    expect(text).toContain('合并规则');
  });

  it('撞上订单数上限时把「能并」与「在等」两个数都说出来', () => {
    const text = groupVerdict({
      openWaveId: null,
      openWaveCode: null,
      eligibleCount: 2,
      waitingCount: 1,
      unruledCount: 0,
    });
    expect(text).toContain('可并入 2 张');
    expect(text).toContain('1 张已撞上订单数上限');
  });
});

describe('成员告警', () => {
  it('跨区、已取消、没拣齐三件事各说各的，不互相吞掉', () => {
    const warnings = memberWarnings({
      crossZoneNote: '横跨多个区，只能进库区不限的规则',
      fullyPicked: false,
      taskStatus: 'CREATED',
    });
    expect(warnings).toHaveLength(2);
    expect(warnings[0]).toContain('横跨多个区');
    expect(warnings[1]).toBe('还没拣齐');
  });

  it('已取消的成员工单不再报「还没拣齐」，改说它仍按成员计', () => {
    const warnings = memberWarnings({ crossZoneNote: null, fullyPicked: false, taskStatus: 'CANCELLED' });
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain('已取消');
    expect(warnings[0]).toContain('仍按它计成员');
  });

  it('拣齐了就是没有告警', () => {
    expect(memberWarnings({ crossZoneNote: null, fullyPicked: true, taskStatus: 'COMPLETED' })).toEqual(
      [],
    );
  });
});

describe('进度与数量', () => {
  it('分母为 0 时给「—」而不是 0%', () => {
    // 「没量过」与「量过了，是 0」在作业台上是两件事。
    expect(progressText(null)).toBe('—');
    expect(progressText(0)).toBe('0%');
    expect(progressText(0.5)).toBe('50%');
    expect(progressText(1)).toBe('100%');
  });

  it('数量去掉无意义的小数尾零，但 0 必须显示成 0', () => {
    expect(qtyText(0)).toBe('0');
    expect(qtyText(10)).toBe('10');
    expect(qtyText(10.5)).toBe('10.5');
    expect(qtyText(10.0)).toBe('10');
    expect(qtyText(null)).toBe('—');
  });
});

describe('波次提示语', () => {
  it('完成提示里必须写明「合并只影响拣货效率，不改变订单粒度」', () => {
    // 不写这句，「已完成」在界面上很容易被读成「货可以发了」。
    expect(waveHint('COMPLETED')).toContain('合并只影响拣货效率，不改变订单粒度');
    expect(waveHint('COMPLETED')).toContain('8.3.3.1');
  });

  it('已排波与拣货中分别说清「能不能改排班」', () => {
    expect(waveHint('PLANNED')).toContain('中途不能再移出成员');
    expect(waveHint('PICKING')).toContain('全部终态');
    // 取消不动库存，这一点要写在界面上。
    expect(waveHint('CANCELLED')).toContain('预占不变');
  });
});

describe('查询串', () => {
  it('只拼给了值的参数，空值一个都不拼', () => {
    expect(pickWaveQuery({})).toBe('');
    expect(pickWaveQuery({ status: 'PLANNED', page: 0, size: 50 })).toBe(
      '?status=PLANNED&page=0&size=50',
    );
    // page=0 是有意义的值，不能被当成「没给」丢掉。
    expect(pickWaveQuery({ page: 0 })).toBe('?page=0');
  });

  it('值做 URL 编码', () => {
    expect(pickWaveQuery({ keyword: 'OB 12&x' })).toBe('?keyword=OB%2012%26x');
  });
});