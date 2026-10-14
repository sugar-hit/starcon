import { describe, expect, it } from 'vitest';
import {
  allowedTargetStatuses,
  directionLabels,
  groupIssues,
  isStatusChange,
  localQuantityGuard,
  movementKindsOf,
  parseScanInput,
  retryableScanText,
  serialQuery,
  statsBalance,
  statusLabels,
  targetLocationRequired,
  targetStatusRequired,
  transitionText,
} from './serial-model';
import type {
  SerialIssue,
  SerialStats,
  SerialValidationResult,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 7.3.2.1 序列号前端模型的纯函数测试。
 *
 * <p>只测纯函数：真正打后端的路径由 `InventorySerialIntegrationTest` 覆盖，
 * 这里守的是「界面上算出来的东西」——扫描解析、数量预检、错误归因、守恒式。
 * 这些如果错了，后端会返回正确答案而界面照样把操作员带沟里。
 */

function issue(partial: Partial<SerialIssue>): SerialIssue {
  return {
    code: 'SERIAL_COUNT_MISMATCH',
    codeLabel: '',
    message: '',
    serialNumber: '',
    expected: '',
    actual: '',
    ...partial,
  } as SerialIssue;
}

describe('parseScanInput', () => {
  it('认换行、逗号与多种分隔符', () => {
    expect(parseScanInput('SN-1\nSN-2\nSN-3')).toEqual(['SN-1', 'SN-2', 'SN-3']);
    expect(parseScanInput('SN-1,SN-2,SN-3')).toEqual(['SN-1', 'SN-2', 'SN-3']);
    expect(parseScanInput('SN-1、SN-2；SN-3')).toEqual(['SN-1', 'SN-2', 'SN-3']);
    expect(parseScanInput('SN-1\tSN-2')).toEqual(['SN-1', 'SN-2']);
  });

  it('去掉首尾空白与空行', () => {
    expect(parseScanInput('\n  SN-1  \n\n  SN-2\n')).toEqual(['SN-1', 'SN-2']);
  });

  it('去重但保持首次出现的顺序', () => {
    expect(parseScanInput('SN-2\nSN-1\nSN-2\nSN-1')).toEqual(['SN-2', 'SN-1']);
  });

  it('不做大小写归一：铭牌上的 sn-001 与 SN-001 可能是两台机器', () => {
    expect(parseScanInput('SN-001\nsn-001')).toEqual(['SN-001', 'sn-001']);
  });

  it('空输入返回空数组而不是含一个空串的数组', () => {
    expect(parseScanInput('')).toEqual([]);
    expect(parseScanInput('  \n \n ')).toEqual([]);
  });
});

describe('localQuantityGuard', () => {
  it('数量与条数一致时放行', () => {
    expect(localQuantityGuard('3', ['a', 'b', 'c'])).toBeNull();
  });

  it('数量与条数不一致时拦下，并同时说出两个数字', () => {
    const guard = localQuantityGuard('3', ['a', 'b']);
    expect(guard?.blocking).toBe(true);
    expect(guard?.message).toContain('3');
    expect(guard?.message).toContain('2');
    expect(guard?.message).toContain('完全一致');
  });

  it('小数按离散件拒绝', () => {
    expect(localQuantityGuard('1.5', ['a'])?.message).toContain('小数');
  });

  it('负数与空值都拦下', () => {
    expect(localQuantityGuard('-1', ['a'])?.message).toContain('负');
    expect(localQuantityGuard('', [])?.message).toContain('必须填写数量');
    expect(localQuantityGuard('abc', ['a'])?.message).toContain('不是数字');
  });

  it('数量为 0 但没扫到任何号时仍然拦下', () => {
    expect(localQuantityGuard('0', [])?.message).toContain('一个序列号都没有');
  });

  it('数量为 0 时用字符串比较而不是浮点：0 与 0.00 不能被判成不等', () => {
    expect(localQuantityGuard('0.00', [])?.message).toContain('一个序列号都没有');
  });
});

describe('方向规则（与后端 resolvePlan 同一套）', () => {
  it('入库要库位、出库不要库位', () => {
    expect(targetLocationRequired('INBOUND')).toBe(true);
    expect(targetLocationRequired('RELOCATION')).toBe(true);
    expect(targetLocationRequired('OUTBOUND')).toBe(false);
    expect(targetLocationRequired('STATUS_CHANGE')).toBe(false);
    expect(targetLocationRequired('REBIND')).toBe(false);
  });

  it('出库与状态流转必须说明目标状态，其余可空', () => {
    expect(targetStatusRequired('OUTBOUND')).toBe(true);
    expect(targetStatusRequired('STATUS_CHANGE')).toBe(true);
    expect(targetStatusRequired('INBOUND')).toBe(false);
    expect(targetStatusRequired('REBIND')).toBe(false);
  });

  it('报废只出现在出库方向', () => {
    expect(allowedTargetStatuses('OUTBOUND')).toContain('SCRAPPED');
    expect(allowedTargetStatuses('INBOUND')).not.toContain('SCRAPPED');
    expect(allowedTargetStatuses('STATUS_CHANGE')).not.toContain('SCRAPPED');
    expect(allowedTargetStatuses('REBIND')).toEqual([]);
  });

  it('状态流转只能停在「有库位」的两档', () => {
    expect(allowedTargetStatuses('STATUS_CHANGE').sort()).toEqual(['IN_STOCK', 'REPAIR']);
  });

  it('每个方向都映射到自己的流转种类，未知方向不猜', () => {
    expect(movementKindsOf('INBOUND')).toEqual(['INBOUND']);
    expect(movementKindsOf('REBIND')).toEqual(['REBIND']);
    expect(movementKindsOf('NOT_A_DIRECTION')).toEqual([]);
  });
});

describe('groupIssues', () => {
  it('把与具体号无关的错误与逐号错误分开', () => {
    const grouped = groupIssues([
      issue({ message: '声明 3', expected: '3', actual: '2' }),
      issue({ serialNumber: 'SN-1', message: '不在指定库位上' }),
      issue({ serialNumber: 'SN-2', message: '不允许的状态流转' }),
    ]);
    expect(grouped.global).toHaveLength(1);
    expect(grouped.bySerial.map((entry) => entry.serialNumber)).toEqual(['SN-1', 'SN-2']);
  });

  it('同一个号的多个问题归到同一条下', () => {
    const grouped = groupIssues([
      issue({ serialNumber: 'SN-1', message: '问题一' }),
      issue({ serialNumber: 'SN-1', message: '问题二' }),
    ]);
    expect(grouped.bySerial).toHaveLength(1);
    expect(grouped.bySerial[0]?.issues).toHaveLength(2);
  });

  it('重复出现的号被单独挑出来', () => {
    const grouped = groupIssues([
      issue({ code: 'SERIAL_DUPLICATED' as SerialIssue['code'], serialNumber: 'SN-1' }),
    ]);
    expect(grouped.duplicates).toEqual(['SN-1']);
  });

  it('空输入不炸', () => {
    expect(groupIssues([])).toEqual({ global: [], bySerial: [], duplicates: [] });
  });
});

describe('retryableScanText', () => {
  it('把需要重扫的号拼成可直接照抄的文本', () => {
    const result = {
      passed: false,
      serialCount: 2,
      issues: [
        issue({ serialNumber: 'SN-1' }),
        issue({ serialNumber: 'SN-2' }),
        issue({ message: '全局错误' }),
      ],
    } as unknown as SerialValidationResult;
    expect(retryableScanText(result)).toBe('SN-1\nSN-2');
    expect(retryableScanText(null)).toBe('');
  });
});

describe('statsBalance', () => {
  const base: SerialStats = {
    total: 6,
    inStock: 2,
    sold: 1,
    inTransit: 1,
    repair: 1,
    scrapped: 1,
    sumMatches: true,
    unboundCount: 1,
    lotMismatchCount: 1,
  };

  it('守恒时没有警告', () => {
    expect(statsBalance(base).ok).toBe(true);
    expect(statsBalance(base).message).toBe('');
  });

  it('五档之和 ≠ 总数时明确警告：那种情况下每个单独的数字都不可信', () => {
    const drifted = { ...base, inStock: 3 };
    const balance = statsBalance(drifted);
    expect(balance.ok).toBe(false);
    expect(balance.message).toContain('7');
    expect(balance.message).toContain('6');
  });

  it('后端说 sumMatches 为 false 时同样报警', () => {
    const drifted = { ...base, sumMatches: false };
    expect(statsBalance(drifted).ok).toBe(false);
  });

  it('没有数据时不算不守恒', () => {
    expect(statsBalance(null).ok).toBe(true);
  });
});

describe('serialQuery', () => {
  it('未传的维度不出现在 query 里', () => {
    expect(serialQuery({})).toBe('');
    expect(serialQuery({ status: '' })).toBe('');
    expect(serialQuery({ status: 'IN_STOCK' })).toBe('?status=IN_STOCK');
  });

  it('关键字与排序会被编码，不会拼出第二个问号', () => {
    expect(serialQuery({ keyword: 'SN 1&x=2' })).toBe('?keyword=SN%201%26x%3D2');
  });

  it('分页参数下限被夹住：负页码会让后端报错而不是返回空', () => {
    expect(serialQuery({ page: -1, size: 0 })).toBe('?page=0&size=1');
  });
});

describe('流转行读法', () => {
  it('空库位显式写成「不在任何库位」而不是留白', () => {
    const text = transitionText(null, 'SH-A1', 'IN_STOCK');
    expect(text).toContain('不在任何库位');
    expect(text).toContain('SH-A1');
  });

  it('只有一端有库位时两侧都读得懂', () => {
    expect(transitionText('SH-A1', null, 'SOLD')).toContain('SH-A1 → 不在任何库位');
  });

  it('换了状态才算状态变更', () => {
    expect(isStatusChange('IN_STOCK', 'SOLD')).toBe(true);
    expect(isStatusChange('IN_STOCK', 'IN_STOCK')).toBe(false);
  });

  it('状态与方向都有中文标签，界面不显示 ASCII 码值', () => {
    expect(statusLabels.IN_STOCK).toBe('在库');
    expect(directionLabels.OUTBOUND).toBe('出库');
    expect(Object.keys(statusLabels)).toHaveLength(5);
    expect(Object.keys(directionLabels)).toHaveLength(5);
  });
});
