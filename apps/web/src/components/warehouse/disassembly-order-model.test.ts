import { describe, expect, it } from 'vitest';
import {
  destinationRoleLabel,
  destinationRoleOptions,
  destinationRoleTone,
  disassemblyOrderQuery,
  disassemblySortKeys,
  disassemblySortLabels,
  disassemblySourceLabel,
  disassemblyStatusLabel,
  disassemblyStatusLabels,
  disassemblyStatusTone,
  entryDirectionLabel,
  formatQuantity,
  invariantsHold,
  isExactAtStockScale,
  isValidRecoveryRate,
} from './disassembly-order-model';

/**
 * 8.4.2.1 拆解单模型的展示口径测试。
 *
 * <p>后端的不变量与校验都已在 Java 集成测试里实测（27 例，Testcontainers 真实 PostgreSQL 16），
 * 这一层守的是**前端自己**的判定：查询串拼装、展示标签、以及
 * 「蓝图加粗句：去向不能默认」在前端有没有被偷偷弱化成「默认选一个」。
 */

describe('拆解单查询串', () => {
  it('只拼传入的筛选项，空对象不带问号', () => {
    expect(disassemblyOrderQuery({})).toBe('');
    expect(disassemblyOrderQuery({ status: 'DRAFT' })).toBe('?status=DRAFT');
  });

  it('关键字与来源会被 URL 编码（否则 & / = 会把查询串撕开）', () => {
    expect(disassemblyOrderQuery({ keyword: 'A&B=C' })).toBe('?keyword=A%26B%3DC');
    // 纯空白的 keyword 不拼：传了等于没传
    expect(disassemblyOrderQuery({ keyword: '   ' })).toBe('');
  });

  it('分页参数用数字原样拼，不做编码', () => {
    expect(disassemblyOrderQuery({ page: 2, size: 50 })).toBe('?page=2&size=50');
  });
});

describe('状态与来源标签', () => {
  it('状态词表与后端 V108 的 CHECK 一一对应', () => {
    expect(Object.keys(disassemblyStatusLabels)).toEqual(['DRAFT', 'COMPLETED', 'CANCELLED']);
    expect(disassemblyStatusLabel('DRAFT')).toBe('草稿');
    expect(disassemblyStatusLabel('COMPLETED')).toBe('已完成');
    expect(disassemblyStatusLabel('CANCELLED')).toBe('已取消');
  });

  it('未知状态原样透出，不吞掉也不瞎猜', () => {
    expect(disassemblyStatusLabel('SHIPPED')).toBe('SHIPPED');
  });

  it('三个上游来源都有中文说法', () => {
    expect(disassemblySourceLabel('DIRECT')).toBe('仓库直接发起');
    expect(disassemblySourceLabel('ASSEMBLY_ORDER')).toBe('来自组装单');
    expect(disassemblySourceLabel('AFTERSALES_RETURN')).toBe('来自售后退件');
    expect(disassemblySourceLabel('SOMETHING_ELSE')).toBe('SOMETHING_ELSE');
  });

  it('三态视觉各不相同（看单子的人最想知道「这批货动过没有」）', () => {
    const tones = ['DRAFT', 'COMPLETED', 'CANCELLED'].map(disassemblyStatusTone);
    expect(new Set(tones).size).toBe(3);
    // 未知状态退到中性灰，而不是随便套一个语义色
    expect(disassemblyStatusTone('SHIPPED')).toBe(disassemblyStatusTone('CANCELLED'));
  });
});

describe('蓝图加粗句：组件去向不能默认', () => {
  it('去向三档正好是蓝图说的可用/待检/残次，且映射到 V1 的 role 词表', () => {
    expect([...destinationRoleOptions]).toEqual(['SELLABLE', 'PENDING_INSPECTION', 'DEFECTIVE']);
    expect(destinationRoleLabel('SELLABLE')).toBe('可用');
    expect(destinationRoleLabel('PENDING_INSPECTION')).toBe('待检');
    expect(destinationRoleLabel('DEFECTIVE')).toBe('残次');
  });

  it('可选去向里**没有**「默认」这一项（不能默认全部回可售）', () => {
    // 少一个就等于允许前端替接收方做主。空值（「请选择去向…」）才是合法起点。
    expect(destinationRoleOptions).not.toContain('');
    expect(destinationRoleOptions).toHaveLength(3);
    expect(
      destinationRoleOptions.some((r) => ['DEFAULT', 'AUTO', 'UNSET', 'ANY'].includes(r)),
    ).toBe(false);
  });

  it('三档去向一眼分得开（可用绿 / 待检琥珀 / 残次红）', () => {
    const tones = destinationRoleOptions.map(destinationRoleTone);
    expect(new Set(tones).size).toBe(3);
  });
});

describe('不变量在页面上的读数', () => {
  const completed = {
    expectedComponentQuantity: 33,
    recoveredComponentQuantity: 33,
    disassemblyQuantity: 3,
    outboundProductQuantity: 3,
    status: 'COMPLETED',
  };

  it('COMPLETED 且两组数相等 → D1 / D2 都成立', () => {
    expect(invariantsHold(completed)).toEqual({ component: true, product: true });
  });

  it('实回 ≠ 应回 → D2 不成立，页面要能显示出来', () => {
    expect(invariantsHold({ ...completed, recoveredComponentQuantity: 30 }).component).toBe(false);
  });

  it('成品出库量 ≠ 拆解数量 → D1 不成立', () => {
    expect(invariantsHold({ ...completed, outboundProductQuantity: 2 }).product).toBe(false);
  });

  it('非终态一律判成立（草稿期实回恒为 0，不该被误报成异常）', () => {
    expect(
      invariantsHold({ ...completed, status: 'DRAFT', recoveredComponentQuantity: 0 }),
    ).toEqual({ component: true, product: true });
    expect(
      invariantsHold({ ...completed, status: 'CANCELLED', outboundProductQuantity: 0 }),
    ).toEqual({ component: true, product: true });
  });
});

describe('数量与刻度', () => {
  it('空值显示破折号而不是 0（0 与「没填」不是一回事）', () => {
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined)).toBe('—');
    expect(formatQuantity('')).toBe('—');
  });

  it('最多 6 位小数，去掉尾随 0', () => {
    expect(formatQuantity(0)).toBe('0');
    expect(formatQuantity(3)).toBe('3');
    expect(formatQuantity('33.500000')).toBe('33.5');
  });

  it('非数字原样透出，不静默变 NaN', () => {
    expect(formatQuantity('abc')).toBe('abc');
  });

  it('4 位库存刻度：能过的过，不能过的拒（不静默取整换「通过」）', () => {
    expect(isExactAtStockScale('3')).toBe(true);
    expect(isExactAtStockScale('0.0001')).toBe(true);
    expect(isExactAtStockScale('1.2345')).toBe(true);
    expect(isExactAtStockScale('1.00001')).toBe(false);
    expect(isExactAtStockScale('0')).toBe(false);
    expect(isExactAtStockScale('-1')).toBe(false);
    expect(isExactAtStockScale('')).toBe(false);
  });

  it('回收率是百分数，取值 [0, 100]；空值放行（等于默认 100 无损）', () => {
    expect(isValidRecoveryRate('')).toBe(true);
    expect(isValidRecoveryRate('100')).toBe(true);
    expect(isValidRecoveryRate('0')).toBe(true);
    expect(isValidRecoveryRate('95.5')).toBe(true);
    expect(isValidRecoveryRate('100.1')).toBe(false);
    expect(isValidRecoveryRate('-1')).toBe(false);
    expect(isValidRecoveryRate('abc')).toBe(false);
  });
});

describe('分录方向与排序', () => {
  it('负向分录是成品出库，正向分录是组件入库', () => {
    expect(entryDirectionLabel('PRODUCT_OUT')).toBe('成品出库');
    expect(entryDirectionLabel('COMPONENT_IN')).toBe('组件入库');
  });

  it('排序键都有中文标签（避免页面直接吐英文枚举）', () => {
    expect(disassemblySortKeys).toEqual([
      'CREATED_AT',
      'ORDER_CODE',
      'DISASSEMBLY_QUANTITY',
      'STATUS',
    ]);
    for (const key of disassemblySortKeys) {
      expect(disassemblySortLabels[key], `${key} 缺标签`).toBeTruthy();
    }
  });
});
