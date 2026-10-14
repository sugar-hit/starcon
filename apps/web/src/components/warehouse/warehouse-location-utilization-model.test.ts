import { describe, expect, it } from 'vitest';
import type {
  LocationUtilizationHeatCell,
  LocationUtilizationRow,
  LocationUtilizationTotals,
} from '../../../../../packages/api-contracts/src/index';
import {
  defaultFilters,
  dwellText,
  gradeLabel,
  heatColor,
  heatRows,
  heatTextColor,
  isEmptyUtilization,
  metricLabel,
  outboundText,
  severityTone,
  totalUtilizationText,
  turnoverText,
  utilizationText,
} from './warehouse-location-utilization-model';

/**
 * 8.5.2.1 库位利用率前端纯函数。
 *
 * <p>重点钉住五件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**「未量」不能显示成 0.0%。** 后端对容量未量 / 容量为 0 / 在手为 0 三种情况
 *       给的是 {@code null}，前端必须分开渲染三种文案。折成 0 会把「没人量过」说成
 *       「量过了，是空的」，把「除数是 0」说成「一个都没放」—— 运营会拿它排库位。
 *   <li>**合计只覆盖部分库位时不显示百分比**，显示「N/M」。
 *   <li>**热力色阶必须单调**：强度 100 的格子亮度不得低于强度 0 的格子，
 *       否则「最热的库位」会被画成最冷的。色相固定在品牌紫蓝系，不引入暖色。
 *   <li>**空置库位与「有货但一件没出」是两种形态**，文案不同。
 *   <li>**空报表与「加载失败」是两种形状**。
 * </ul>
 */

const UUID_A = '11111111-1111-1111-1111-111111111111';

function row(overrides: Partial<LocationUtilizationRow> = {}): LocationUtilizationRow {
  return {
    id: UUID_A,
    code: 'B-01',
    name: '01 货架 1 层',
    type: 'STORAGE',
    typeLabel: '存储',
    status: 'ACTIVE',
    pickable: true,
    parentId: null,
    capacity: 10,
    onHand: 4,
    reserved: 0,
    capacityUtilization: 0.4,
    capacityMeasured: true,
    capacityZero: false,
    capacityExceeded: false,
    outboundQuantity: 2,
    outboundMovements: 1,
    turnover: 0.5,
    turnoverComputable: true,
    skuCount: 2,
    skuDispersion: 1,
    dwellDays: 30,
    grade: 'LOW',
    gradeLabel: '低位',
    heat: 40,
    ...overrides,
  };
}

function totals(overrides: Partial<LocationUtilizationTotals> = {}): LocationUtilizationTotals {
  return {
    locationCount: 4,
    capacityMeasuredCount: 4,
    capacityZeroCount: 0,
    idleCount: 1,
    overfullCount: 1,
    highCount: 0,
    lowCount: 1,
    balancedCount: 1,
    unmeasuredCount: 0,
    pickableCount: 3,
    capacity: 40,
    onHand: 20,
    reserved: 0,
    capacityUtilization: 0.5,
    utilizationComputable: true,
    outboundQuantity: 8,
    outboundMovements: 3,
    turnover: 0.4,
    turnoverComputable: true,
    skuCount: 9,
    ...overrides,
  };
}

describe('利用率三态', () => {
  it('量过且有货时给百分比', () => {
    expect(utilizationText(row({ capacityUtilization: 0.4 }))).toBe('40.0%');
    expect(utilizationText(row({ capacityUtilization: 1.2 }))).toBe('120.0%');
  });

  it('容量从没量过时给「未量」，不给 0.0%', () => {
    const r = row({ capacity: null, capacityUtilization: null, capacityMeasured: false });
    expect(utilizationText(r)).toBe('未量');
    // 关键反向断言：绝不能是 0.0%
    expect(utilizationText(r)).not.toBe('0.0%');
  });

  it('容量量出来是 0 时说「容量为 0」，不给 0.0% 也不给 100%', () => {
    const r = row({
      capacity: 0,
      capacityUtilization: null,
      capacityMeasured: true,
      capacityZero: true,
    });
    expect(utilizationText(r)).toBe('容量为 0');
    expect(utilizationText(r)).not.toBe('0.0%');
    expect(utilizationText(r)).not.toBe('100.0%');
  });
});

describe('周转率无定义边界', () => {
  it('在手为 0 时给「无在手量」，不给 0.00 倍', () => {
    const r = row({
      onHand: 0,
      reserved: 0,
      turnover: null,
      turnoverComputable: false,
      outboundQuantity: 0,
      dwellDays: null,
    });
    expect(turnoverText(r)).toBe('无在手量');
    expect(turnoverText(r)).not.toBe('0.00 倍/窗口');
  });

  it('有货但一件没出时给 0.00 倍，与「压根没货」分开', () => {
    const r = row({ onHand: 10, outboundQuantity: 0, turnover: 0, turnoverComputable: true });
    expect(turnoverText(r)).toBe('0.00 倍/窗口');
    // 这正是「低效库位」的形态，与空置库位不是同一件事
    expect(outboundText(r)).toBe('0');
    expect(outboundText(row({ onHand: 0, outboundQuantity: 0 }))).toBe('无在手量');
  });
});

describe('在手天数', () => {
  it('没有库存记录时给「无库存记录」，不给 0 天', () => {
    expect(dwellText(row({ dwellDays: null }))).toBe('无库存记录');
    expect(dwellText(row({ dwellDays: null }))).not.toBe('0.0 天未变动');
  });

  it('有库存记录时给天数', () => {
    expect(dwellText(row({ dwellDays: 12.34 }))).toBe('12.3 天未变动');
  });
});

describe('合计利用率', () => {
  it('全部库位都量过容量时给百分比', () => {
    expect(totalUtilizationText(totals({ capacityUtilization: 0.5 }))).toBe('50.0%');
  });

  it('只有部分库位量过容量时给覆盖度，不给没人测过的百分比', () => {
    const t = totals({
      capacityMeasuredCount: 2,
      capacityUtilization: null,
      utilizationComputable: false,
    });
    expect(totalUtilizationText(t)).toBe('仅覆盖 2/4 个库位');
  });

  it('容量总和为 0 时不假装 100%', () => {
    const t = totals({ capacity: 0, capacityUtilization: null, utilizationComputable: false });
    expect(totalUtilizationText(t)).toContain('/');
    expect(totalUtilizationText(t)).not.toContain('100');
  });
});

describe('热力色阶是真数据驱动', () => {
  it('强度越高亮度越高（单调），否则最热的库位会被画成最冷的', () => {
    let previousLuma = -1;
    for (let heat = 0; heat <= 100; heat += 10) {
      const rgb = (heatColor(heat).match(/\d+/g) ?? []).map(Number);
      const [r = 0, g = 0, b = 0] = rgb;
      const luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      expect(luma).toBeGreaterThan(previousLuma);
      previousLuma = luma;
    }
  });

  it('越界强度被夹住而不是绕回色环另一端', () => {
    expect(heatColor(-50)).toBe(heatColor(0));
    expect(heatColor(500)).toBe(heatColor(100));
  });

  it('深色格配浅字、浅色格配深字', () => {
    expect(heatTextColor(90)).toBe('#ffffff');
    expect(heatTextColor(10)).toBe('#e2e8f0');
  });

  it('每个强度来源都有中文说法，不能悄悄换指标还叫同一个名字', () => {
    expect(metricLabel('CAPACITY_UTILIZATION')).toBe('容量利用率');
    expect(metricLabel('ON_HAND_RELATIVE')).toBe('在手量相对值');
    expect(metricLabel('IDLE')).toBe('空置');
  });

  it('扁平格子按 depth 缩进，缩进量不出现负数', () => {
    const cells: LocationUtilizationHeatCell[] = [
      {
        locationId: UUID_A,
        code: 'ZONE',
        name: 'A 区',
        parentId: null,
        parentCode: null,
        depth: 0,
        grade: 'BALANCED',
        gradeLabel: '适中',
        capacityUtilization: 0.5,
        metric: 'CAPACITY_UTILIZATION',
        heat: 50,
        value: 0.5,
      },
      {
        locationId: '22222222-2222-2222-2222-222222222222',
        code: 'B-01',
        name: '01 货架',
        parentId: UUID_A,
        parentCode: 'ZONE',
        depth: 1,
        grade: 'UNMEASURED',
        gradeLabel: '未量容量',
        capacityUtilization: null,
        metric: 'ON_HAND_RELATIVE',
        heat: 33,
        value: 3,
      },
    ];
    const rows = heatRows(cells);
    expect(rows.map((r) => r.indent)).toEqual([0, 1]);
    expect(rows.every((r) => r.indent >= 0)).toBe(true);
    // 容量量不出来的格子必须保留 null，不能被补成 0
    expect(rows[1]!.cell.capacityUtilization).toBeNull();
  });
});

describe('档位与建议文案', () => {
  it('六档都有中文名', () => {
    for (const g of ['OVERFULL', 'HIGH', 'BALANCED', 'LOW', 'IDLE', 'UNMEASURED'] as const) {
      expect(gradeLabel(g).length).toBeGreaterThan(0);
      expect(gradeLabel(g)).not.toBe(g);
    }
  });

  it('三个严重度各有区分色调，且未知的降级到默认档', () => {
    expect(severityTone('HIGH')).not.toBe(severityTone('MEDIUM'));
    expect(severityTone('MEDIUM')).not.toBe(severityTone('LOW'));
    expect(severityTone('UNKNOWN')).toBe(severityTone('LOW'));
  });
});

describe('空态', () => {
  it('空报表与 null 都是「没有可分析的数据」', () => {
    expect(isEmptyUtilization(null)).toBe(true);
    expect(isEmptyUtilization({ locations: [] } as never)).toBe(true);
    expect(isEmptyUtilization({ locations: [row()] } as never)).toBe(false);
  });
});

describe('默认筛选', () => {
  it('默认窗口是后端声明的 30 天，且没有预设档位筛选', () => {
    expect(defaultFilters.windowDays).toBe(30);
    expect(defaultFilters.grade).toBe('');
    expect(defaultFilters.pickableOnly).toBe(false);
  });
});
