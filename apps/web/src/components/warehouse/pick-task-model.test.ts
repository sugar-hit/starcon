import { describe, expect, it } from 'vitest';
import type { PickTaskRow } from '../../../../../packages/api-contracts/src/index';
import {
  canCancel,
  canComplete,
  canRecordPick,
  canReopen,
  canReportException,
  exceptionKindLabel,
  lineStatusLabel,
  lineWarnings,
  locationDrifted,
  needsShortageQuantity,
  pickCompletionText,
  progressText,
  qtyText,
  resolutionLabel,
  serialText,
  shortfallText,
  statusLabel,
  statusTone,
} from './pick-task-model';

/**
 * 8.3.2.1 拣货任务前端模型的展示口径。
 *
 * <p>这些断言钉的都是「界面上最容易说错、而静态检查一条都查不出来」的地方：
 * 「没量过」与「量过了是 0」、「拣了多少」与「拣齐了没」、「分配」与「实际」、
 * 「没货可拣」与「还没拣」。写错它们不会让 typecheck 红，只会让作业员白跑一趟库位。
 */

const row = (over: Partial<PickTaskRow>): PickTaskRow =>
  ({
    plannedLineCount: 3,
    pickedLineCount: 2,
    pickedQuantity: 12,
    plannedQuantity: 20,
    fullyPicked: false,
    status: 'IN_PROGRESS',
    ...over,
  }) as PickTaskRow;

describe('状态与按钮可用性', () => {
  it('四态里没有「已发货」——拣货完不等于已发货', () => {
    expect(statusLabel('CREATED')).toBe('已生成');
    expect(statusLabel('IN_PROGRESS')).toBe('拣货中');
    expect(statusLabel('COMPLETED')).toBe('已完成');
    expect(statusLabel('CANCELLED')).toBe('已取消');
    // 已取消不是「完成」：配色必须不同，否则作业台看起来像多了完成量。
    expect(statusTone('CANCELLED')).not.toBe(statusTone('COMPLETED'));
  });

  it('只有「拣货中」能登记实拣与完成；终态只能重开或取消不了', () => {
    expect(canRecordPick('IN_PROGRESS')).toBe(true);
    expect(canRecordPick('CREATED')).toBe(false);
    expect(canRecordPick('COMPLETED')).toBe(false);
    expect(canComplete('IN_PROGRESS')).toBe(true);
    expect(canComplete('CREATED')).toBe(false);
    expect(canReopen('COMPLETED')).toBe(true);
    expect(canReopen('CANCELLED')).toBe(true);
    expect(canReopen('IN_PROGRESS')).toBe(false);
    expect(canCancel('CREATED')).toBe(true);
    expect(canCancel('COMPLETED')).toBe(false);
    // 异常可在已生成与拣货中上报（分配错了也要能说），终态不行。
    expect(canReportException('CREATED')).toBe(true);
    expect(canReportException('COMPLETED')).toBe(false);
  });
});

describe('进度：没量过与量过了是 0', () => {
  it('分母为 0 时给「—」而不是 0%', () => {
    expect(progressText(null)).toBe('—');
    expect(progressText(0)).toBe('0%');
    expect(progressText(1)).toBe('100%');
    expect(progressText(0.6)).toBe('60%');
    expect(progressText(0.12345)).toBe('12.3%');
  });

  it('「拣了多少件」与「拣齐了没」是两回事', () => {
    expect(pickCompletionText(row({}))).toBe('2/3 段拣齐');
    expect(pickCompletionText(row({ fullyPicked: true }))).toBe('已拣齐');
    // 一段都没排出来时不能显示成 0/0，那读起来像「一个都没拣」。
    expect(pickCompletionText(row({ plannedLineCount: 0 }))).toBe('未排出计划');
  });

  it('数量 0 必须显示成 0 而不是空串或 —', () => {
    expect(qtyText(0)).toBe('0');
    expect(qtyText(null)).toBe('—');
    expect(qtyText(10)).toBe('10');
    expect(qtyText(0.5)).toBe('0.5');
  });

  it('只有还差件的时候才说缺口', () => {
    expect(shortfallText(0)).toBe('已拣齐');
    expect(shortfallText(3)).toBe('还差 3 件');
  });
});

describe('行状态', () => {
  it('三态各有说法', () => {
    expect(lineStatusLabel('PENDING')).toBe('待拣');
    expect(lineStatusLabel('PICKED')).toBe('拣齐');
    expect(lineStatusLabel('SHORT')).toBe('拣不齐');
  });
});

describe('8.5.2.1 两条建议在拣货路径上的落地', () => {
  it('三条警示是并列的：一行完全可以既散在多库位又只能退到不可拣位', () => {
    expect(
      lineWarnings({ nonPickableSource: true, skuLocationCount: 3, sourceHasStock: true }),
    ).toHaveLength(2);
    expect(
      lineWarnings({ nonPickableSource: true, skuLocationCount: 3, sourceHasStock: true })[0],
    ).toContain('不可拣');
    expect(
      lineWarnings({ nonPickableSource: true, skuLocationCount: 3, sourceHasStock: true })[1],
    ).toContain('散在 3 个库位');
  });

  it('一行什么都没有时一条警示都不给（不凑数）', () => {
    expect(
      lineWarnings({ nonPickableSource: false, skuLocationCount: 1, sourceHasStock: true }),
    ).toEqual([]);
  });

  it('「没货可拣」与「还没拣」分开说：前者是仓里没有，后者是还没开始', () => {
    const warnings = lineWarnings({
      nonPickableSource: false,
      skuLocationCount: 1,
      sourceHasStock: false,
    });
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain('无可分配库存');
    expect(warnings[0]).not.toContain('待拣');
  });

  it('警示文案回指建议来源模块，不让读者去猜这是谁提的', () => {
    expect(
      lineWarnings({ nonPickableSource: true, skuLocationCount: 1, sourceHasStock: true })[0],
    ).toContain('8.5.2.1');
    expect(
      lineWarnings({ nonPickableSource: false, skuLocationCount: 2, sourceHasStock: true })[0],
    ).toContain('8.5.2.1');
  });
});

describe('分配是建议、现场是事实', () => {
  it('实际库位与计划不同要能被识别出来', () => {
    expect(locationDrifted({ sourceLocationId: 'a' }, { locationId: 'a' })).toBe(false);
    expect(locationDrifted({ sourceLocationId: 'a' }, { locationId: 'b' })).toBe(true);
  });

  it('序列号逐个列出（复核要按号核对），没有序列号时给空串而不是占位', () => {
    expect(
      serialText({
        serialCount: 0,
        serials: [],
      } as never),
    ).toBe('');
    expect(
      serialText({
        serialCount: 2,
        serials: [{ serialNumber: 'SN-1' }, { serialNumber: 'SN-2' }],
      } as never),
    ).toBe('SN-1、SN-2');
  });
});

describe('异常', () => {
  it('只有缺货要填数量（后端 CHECK 的镜像，其余四类填了会被拒）', () => {
    expect(needsShortageQuantity('SHORTAGE')).toBe(true);
    expect(needsShortageQuantity('DAMAGED')).toBe(false);
    expect(needsShortageQuantity('MISSING_SERIAL')).toBe(false);
    expect(needsShortageQuantity('WRONG_LOCATION')).toBe(false);
    expect(needsShortageQuantity('OTHER')).toBe(false);
  });

  it('种类与处置各有中文名', () => {
    expect(exceptionKindLabel('SHORTAGE')).toBe('缺货');
    expect(exceptionKindLabel('DAMAGED')).toBe('破损');
    expect(resolutionLabel('REPLACED')).toBe('换货');
    expect(resolutionLabel('WRITTEN_OFF')).toBe('报损');
  });
});
