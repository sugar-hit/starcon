import { describe, expect, it } from 'vitest';
import type { WarehouseRow } from '../../../../../packages/api-contracts/src/index';
import {
  ALL,
  countryOptions,
  momentText,
  createProblem,
  isPlatformObserved,
  kindLabel,
  kindLabels,
  legalEntityText,
  localTimeText,
  qty,
  stockDisplay,
  statusLabel,
  statusTone,
  workText,
} from './warehouse-facility-model';

/**
 * 8.1.1.1 仓库列表前端纯函数。
 *
 * <p>重点钉住四件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**平台仓观测值与自有账本不相加**。合成一个「总库存」会让人以为那是账实相符的数字。
 *   <li>**未绑定法人要说未绑定**。「法人数 0」（没货）与「没绑定法人」（有货）不是一件事。
 *   <li>**时区不能只留偏移量**。填 {@code +08:00} 的人在界面上要立刻被拦住。
 *   <li>**归档仓不能被当成可用仓**推给用户。
 * </ul>
 */

function row(overrides: Partial<WarehouseRow> = {}): WarehouseRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    code: 'WH-A',
    name: '自营仓',
    kind: 'LOCAL',
    kindLabel: '自营仓',
    countryCode: 'CN',
    timezone: 'Asia/Shanghai',
    localTime: '2026-10-07 20:15',
    status: 'ACTIVE',
    defaultWarehouse: false,
    legalEntityId: '22222222-2222-2222-2222-222222222222',
    legalEntityCode: 'LE-1',
    legalEntityName: '主体一',
    legalEntityCount: 1,
    locationCount: 3,
    skuCount: 12,
    stock: { onHand: 150, reserved: 10, available: 140, balanceRowCount: 4 },
    platformStock: null,
    work: {
      stocktakeTaskCount: 2,
      transferInCount: 1,
      transferOutCount: 1,
      inventoryMovementCount: 7,
      workOrderTotal: 4,
      openWorkOrderCount: 2,
    },
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('仓库类型词表覆盖蓝图点名的五类', () => {
  it('五种类型都有中文名', () => {
    expect(Object.keys(kindLabels).sort()).toEqual(
      ['BONDED', 'LOCAL', 'OVERSEAS', 'PLATFORM_FBA', 'THIRD_PARTY'].sort(),
    );
    expect(kindLabel('BONDED')).toBe('监管仓');
    expect(kindLabel('LOCAL')).toBe('自营仓');
    expect(kindLabel('OVERSEAS')).toBe('海外仓');
    expect(kindLabel('PLATFORM_FBA')).toBe('平台仓');
    expect(kindLabel('THIRD_PARTY')).toBe('三方仓');
  });

  it('只有平台仓的数量来自观测值', () => {
    // 监管仓（保税仓）的货是自有货，只是海关监管状态 —— 它记自有账本，不是观测值。
    expect(isPlatformObserved('PLATFORM_FBA')).toBe(true);
    expect(isPlatformObserved('BONDED')).toBe(false);
    expect(isPlatformObserved('LOCAL')).toBe(false);
    expect(isPlatformObserved('OVERSEAS')).toBe(false);
    expect(isPlatformObserved('THIRD_PARTY')).toBe(false);
  });
});

describe('平台仓观测值与自有账本不相加', () => {
  it('自有仓只显示自有账本', () => {
    const display = stockDisplay(row());
    expect(display.source).toBe('ledger');
    expect(display.onHand).toBe('150');
    expect(display.available).toBe('140');
    expect(display.note).toBe('自有账本');
  });

  it('平台仓只显示观测值且标明来源与采集时间', () => {
    const display = stockDisplay(
      row({
        kind: 'PLATFORM_FBA',
        stock: { onHand: 0, reserved: 0, available: 0, balanceRowCount: 0 },
        platformStock: {
          sourceCount: 2,
          observedOnHand: 310,
          observedAvailable: 280,
          observedInbound: 50,
          latestCollectedAt: '2026-10-07T10:40:00Z',
        },
      }),
    );
    expect(display.source).toBe('observed');
    expect(display.onHand).toBe('310');
    expect(display.available).toBe('280');
    // 必须说清「不计入自有账本」与新鲜度，否则这个数会被当成和自有仓一样确定的数。
    expect(display.note).toContain('平台仓观测值');
    expect(display.note).toContain('不计入自有账本');
    expect(display.note).toContain('2026-10-07 10:40 Z');
  });
});

describe('货权法人的三种状态要分开说', () => {
  it('已绑定：显示编码与名称', () => {
    expect(legalEntityText(row())).toBe('LE-1 · 主体一');
  });

  it('没绑定但有货：这是最危险的一种，必须说出来', () => {
    expect(legalEntityText(row({ legalEntityId: null, legalEntityCode: null, legalEntityName: null, legalEntityCount: 2 })))
      .toBe('未绑定法人（已持有 2 个法人的货）');
  });

  it('没绑定且没货：如实说未绑定', () => {
    expect(legalEntityText(row({ legalEntityId: null, legalEntityCode: null, legalEntityName: null, legalEntityCount: 0 })))
      .toBe('未绑定法人');
  });
});

describe('时区', () => {
  it('给 IANA ID 与当地时间两个信息，不给裸偏移量', () => {
    expect(localTimeText(row())).toBe('2026-10-07 20:15（Asia/Shanghai）');
  });

  it('库里时区无法识别时说清楚，而不是静默显示一个错的时刻', () => {
    expect(localTimeText(row({ timezone: 'Not/AZone', localTime: null }))).toBe('时区 Not/AZone 无法识别');
  });

  it('填偏移量而不是 IANA ID 时在表单层就拦住', () => {
    const base = {
      code: 'WH-X',
      name: '新仓',
      kind: 'LOCAL' as const,
      countryCode: 'CN',
      timezone: '+08:00',
      defaultLegalEntityId: 'le-1',
      status: 'ACTIVE',
      isDefault: false,
    };
    expect(createProblem(base)).toContain('IANA ID');
    expect(createProblem({ ...base, timezone: 'Asia/Shanghai' })).toBe('');
  });
});

describe('作业量摘要逐项给出而不是只给总数', () => {
  it('四个已接入维度各说各的', () => {
    expect(workText(row())).toBe('盘点 2 · 调入 1 · 调出 1 · 库存变动 7');
  });
});

describe('状态', () => {
  it('归档仓是负向色调，不能被当成可用仓', () => {
    expect(statusLabel('ARCHIVED')).toBe('已归档');
    expect(statusTone('ARCHIVED')).toBe('negative');
    expect(statusTone('ACTIVE')).toBe('positive');
    expect(statusTone('INACTIVE')).toBe('neutral');
  });
});

describe('数量格式化', () => {
  it('缺值显示破折号而不是 0', () => {
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
  });

  it('整数与小数都按千分位显示', () => {
    expect(qty(1234)).toBe('1,234');
    expect(qty(1234.5)).toBe('1,234.5');
  });
});

describe('新增表单校验', () => {
  const ok = {
    code: 'WH-X',
    name: '新仓',
    kind: 'BONDED' as const,
    countryCode: 'CN',
    timezone: 'Asia/Shanghai',
    defaultLegalEntityId: 'le-1',
    status: 'ACTIVE',
    isDefault: false,
  };

  it('全部填对时没有错误', () => {
    expect(createProblem(ok)).toBe('');
  });

  it('仓库绑定法人是财务前提，所以法人必填', () => {
    expect(createProblem({ ...ok, defaultLegalEntityId: '' })).toContain('货权');
  });

  it('国家地区必须是两位字母代码', () => {
    expect(createProblem({ ...ok, countryCode: 'CHN' })).toContain('两位字母');
    expect(createProblem({ ...ok, countryCode: 'cn' })).toBe('');
  });

  it('类型与编码不能为空', () => {
    expect(createProblem({ ...ok, kind: '' as never })).toContain('仓库类型');
    expect(createProblem({ ...ok, code: '  ' })).toContain('仓库编码');
    expect(createProblem({ ...ok, name: '  ' })).toContain('仓库名称');
  });
});

describe('时刻渲染按自带偏移量换算，不猜时区', () => {
  it('Z 与 ±HH:MM 都压成可读形状并保留偏移', () => {
    expect(momentText('2026-10-07T10:40:00Z')).toBe('2026-10-07 10:40 Z');
    expect(momentText('2026-10-07T18:40:00+08:00')).toBe('2026-10-07 18:40 +08:00');
  });

  it('缺值说破折号而不是当下时间', () => {
    // 缺采集时间时画成「现在几点」会被读成「刚采集过」。
    expect(momentText(null)).toBe('—');
    expect(momentText(undefined)).toBe('—');
  });

  it('形状不认识时原样返回，不给一个错位的时刻', () => {
    expect(momentText('昨天')).toBe('昨天');
    expect(momentText('')).toBe('—');
  });
});

describe('国家下拉只列真实数据', () => {
  it('空列表时给空数组而不是内置国别表', () => {
    expect(countryOptions([])).toEqual([]);
    expect(countryOptions([{ code: 'CN' }, { code: 'US' }])).toEqual([
      { value: 'CN', label: 'CN' },
      { value: 'US', label: 'US' },
    ]);
  });

  it('全部筛选项是空串而不是 undefined', () => {
    expect(ALL).toBe('');
  });
});