import { describe, expect, it } from 'vitest';
import type { ConfigOptions } from '../../../../../packages/api-contracts/src/index';
import {
  boolText,
  changeText,
  configProblem,
  draftOf,
  draftOfShift,
  durationText,
  emptyShiftDraft,
  isBoolField,
  isRatioField,
  ratioText,
  ratioValueText,
  savedText,
  scopeLabel,
  shiftProblem,
  strategyLabel,
  strategyNote,
  unconfiguredText,
  updateConfig,
  windowText,
  type ConfigDraft,
  type ShiftDraft,
} from './warehouse-config-model';

/**
 * 8.1.1.3 仓库配置前端纯函数。
 *
 * <p>重点钉住六件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**「未配置」与「配成默认值」分开。**否则用户会以为复核比例 0 是有人特意设的。
 *   <li>**留痕里的值要还原成人话。**所有值都是规范化文本（"0.050000"/"true"/"WAVE"），
 *       直接渲染出来用户读不懂。
 *   <li>**比例展示统一百分比。**不给人看 6 位小数冒充精度。
 *   <li>**跨夜班的时长要说清跨到了次日。**22:00 → 06:00 是 8 小时，不是 −16 小时。
 *   <li>**表单在界面上先拦。**后端也独立校验，前端校验是体验不是安全边界。
 *   <li>**提交后要告诉用户写了几条留痕** —— 蓝图的留痕要求要让用户看得见。
 * </ul>
 */

const options: ConfigOptions = {
  strategies: [
    { code: 'ORDER', label: '按订单', note: '一单一任务：责任到单，但订单数多时任务碎片化' },
    { code: 'WAVE', label: '按波次', note: '一批一任务：同波次订单一起拣，路径短' },
    { code: 'LOCATION', label: '按库位', note: '一库位一任务：适合货位集中的仓' },
  ],
  shifts: [],
  scopes: [
    { code: 'CONFIG', label: '作业参数' },
    { code: 'SHIFT', label: '班次字段' },
    { code: 'SHIFT_SCHEDULE', label: '班次启用状态' },
  ],
  reviewRatioNote: '默认复核比例是 0~1 的比例值',
  splitShipmentNote: '拆零发货默认关闭',
  shiftNote: '班次存的是当地墙上时刻',
};

function draft(overrides: Partial<ConfigDraft> = {}): ConfigDraft {
  return {
    lotTrackingEnabled: false,
    serialTrackingEnabled: false,
    pickingStrategy: 'ORDER',
    defaultReviewRatio: '0.000000',
    allowSplitShipment: false,
    note: '',
    ...overrides,
  };
}

describe('未配置与默认值必须分开', () => {
  it('未配置时给出说明，配过之后不再提示', () => {
    expect(unconfiguredText({ unconfigured: true })).toContain('尚未配置');
    // 「配成默认值」不是「未配置」—— 这两件事对运营完全不同
    expect(unconfiguredText({ unconfigured: false })).toBe('');
  });

  it('草稿里的比例按 6 位小数取，与服务端归一口径一致', () => {
    const d = draftOf({
      warehouseCode: 'WH-A',
      warehouseName: '自营仓',
      timezone: 'Asia/Shanghai',
      warehouseStatus: 'ACTIVE',
      archived: false,
      editable: true,
      config: {
        warehouseId: 'w',
        lotTrackingEnabled: true,
        serialTrackingEnabled: false,
        pickingStrategy: 'WAVE',
        defaultReviewRatio: 0.05,
        allowSplitShipment: false,
        unconfigured: false,
        note: '夜班为主',
        updatedByMembershipId: null,
        createdAt: null,
        updatedAt: null,
      },
      shifts: [],
      changes: { rows: [], total: 0, page: 0, size: 20 },
      impacts: [],
      note: '',
    });
    expect(d.defaultReviewRatio).toBe('0.050000');
    expect(d.pickingStrategy).toBe('WAVE');
    expect(d.lotTrackingEnabled).toBe(true);
    expect(d.note).toBe('夜班为主');
  });

  it('0 比例渲染成 0.00% 而不是 6 位小数', () => {
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0.05)).toBe('5.00%');
    expect(ratioText(1)).toBe('100.00%');
  });
});

describe('留痕值的展示', () => {
  it('比例值换算成百分比，与配置页同一口径', () => {
    expect(ratioValueText('0.050000')).toBe('5.00%');
    expect(ratioValueText('0')).toBe('0.00%');
  });

  it('「之前没有这个值」与「之前是空」显示成不同的话', () => {
    // null = 首次配置时这个字段没有旧值；空串 = 旧值是空。
    // 两者混成同一个「(空)」就会让复盘分不清「新建」与「清空」。
    expect(ratioValueText(null)).toBe('（无）');
    expect(boolText(null)).toBe('（无）');
    expect(boolText('true')).toBe('是');
    expect(boolText('false')).toBe('否');
  });

  it('逐字段渲染「从什么 → 什么」', () => {
    const line = changeText(
      { fieldName: 'defaultReviewRatio', oldValue: '0.000000', newValue: '0.100000' },
      isBoolField,
      isRatioField,
    );
    expect(line.from).toBe('0.00%');
    expect(line.to).toBe('10.00%');

    const flag = changeText(
      { fieldName: 'allowSplitShipment', oldValue: 'false', newValue: 'true' },
      isBoolField,
      isRatioField,
    );
    expect(flag.from).toBe('否');
    expect(flag.to).toBe('是');

    // 非布尔非比例的字段原样渲染（策略名、备注）
    const strategy = changeText(
      { fieldName: 'pickingStrategy', oldValue: 'ORDER', newValue: 'WAVE' },
      isBoolField,
      isRatioField,
    );
    expect(strategy.to).toBe('WAVE');
  });

  it('字段类型判定覆盖六个配置字段与两个班次字段', () => {
    expect(isBoolField('lotTrackingEnabled')).toBe(true);
    expect(isBoolField('serialTrackingEnabled')).toBe(true);
    expect(isBoolField('allowSplitShipment')).toBe(true);
    expect(isBoolField('enabled')).toBe(true);
    expect(isRatioField('defaultReviewRatio')).toBe(true);
    expect(isBoolField('pickingStrategy')).toBe(false);
    expect(isRatioField('note')).toBe(false);
  });

  it('留痕范围的中文名与后端三个取值对应', () => {
    expect(scopeLabel('CONFIG')).toBe('作业参数');
    expect(scopeLabel('SHIFT')).toBe('班次字段');
    expect(scopeLabel('SHIFT_SCHEDULE')).toBe('班次启用状态');
  });

  it('保存后告诉用户写了几条留痕；没变化时明说没有新增', () => {
    expect(savedText(3)).toContain('3 条');
    expect(savedText(0)).toContain('没有新增变更留痕');
  });
});

describe('班次时长与时段', () => {
  it('跨夜班的时长说清跨到了次日', () => {
    expect(durationText(480, true)).toBe('8 小时（跨夜，至次日）');
    expect(durationText(540, false)).toBe('9 小时');
    expect(durationText(555, false)).toBe('9 小时 15 分');
  });

  it('时段展示带「跨夜」标记', () => {
    expect(windowText('09:00:00', '18:00:00', false)).toBe('09:00:00 → 18:00:00');
    expect(windowText('22:00:00', '06:00:00', true)).toBe('22:00:00 → 06:00:00（跨夜）');
  });

  it('编辑已有班次时编码带上且不可改的提示', () => {
    const d = draftOfShift({
      id: 's1',
      code: 'N',
      name: '夜班',
      startLocal: '22:00:00',
      endLocal: '06:00:00',
      enabled: false,
      note: '',
    });
    expect(d.code).toBe('N');
    expect(d.enabled).toBe(false);
  });

  it('新建班次的草稿有可用的默认值', () => {
    const d = emptyShiftDraft();
    expect(d.code).toBe('');
    expect(d.startLocal).toBe('09:00');
    expect(d.enabled).toBe(true);
  });
});

describe('表单校验', () => {
  it('合法草稿通过', () => {
    expect(configProblem(draft({ defaultReviewRatio: '0.05', pickingStrategy: 'WAVE' }))).toBe('');
  });

  it('比例越界被拦下并说清 0/1 各代表什么', () => {
    expect(configProblem(draft({ defaultReviewRatio: '1.5' }))).toContain('0 到 1');
    expect(configProblem(draft({ defaultReviewRatio: '-0.1' }))).toContain('0 到 1');
    expect(configProblem(draft({ defaultReviewRatio: '' }))).toContain('请填写');
    expect(configProblem(draft({ defaultReviewRatio: 'abc' }))).toContain('必须是数字');
  });

  it('拣货策略只认三选一', () => {
    expect(configProblem(draft({ pickingStrategy: 'PICK_LIST' }))).toContain('拣货策略');
  });

  it('班次时刻格式与零长班被拦下', () => {
    const good: ShiftDraft = { code: 'D', name: '白班', startLocal: '09:00', endLocal: '18:00', enabled: true, note: '' };
    expect(shiftProblem(good, false)).toBe('');
    expect(shiftProblem({ ...good, code: '  ' }, false)).toContain('班次编码');
    expect(shiftProblem({ ...good, name: '' }, false)).toContain('班次名称');
    expect(shiftProblem({ ...good, startLocal: '九点' }, false)).toContain('HH:mm');
    expect(shiftProblem({ ...good, endLocal: '18:60' }, false)).toContain('HH:mm');
    // 上下班相同会被当成跨夜 24 小时的班，界面上先拦
    expect(shiftProblem({ ...good, endLocal: '09:00' }, false)).toContain('不能相同');
  });
});

describe('策略下拉', () => {
  it('三选一各自有中文名与「为什么这么选」的说明', () => {
    expect(strategyLabel('WAVE', options)).toBe('按波次');
    expect(strategyNote('WAVE', options)).toContain('路径短');
    // 未知策略不静默变成空串，原样透出让人看得见
    expect(strategyLabel('PICK_LIST', options)).toBe('PICK_LIST');
    expect(strategyNote('PICK_LIST', null)).toBe('');
  });
});

describe('提交载荷', () => {
  it('改配置走 PUT 并把备注 trim 掉', async () => {
    const calls: unknown[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method, body: init.body });
      return new Response(JSON.stringify({ config: {}, changeCount: 2, changes: [] }));
    }) as typeof fetch;
    try {
      await updateConfig('w1', draft({ note: '  夜班为主  ', defaultReviewRatio: '0.05' }));
    } finally {
      globalThis.fetch = original;
    }
    const call = calls[0] as { url: string; method: string; body: string };
    expect(call.url).toContain('warehouse/facilities/w1/config');
    expect(call.method).toBe('PUT');
    expect(JSON.parse(call.body)).toMatchObject({ note: '夜班为主', defaultReviewRatio: 0.05 });
  });
});
