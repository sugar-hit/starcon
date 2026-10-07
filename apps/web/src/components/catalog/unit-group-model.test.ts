import { describe, expect, it } from 'vitest';
import type {
  UnitGroupConvertResult,
  UnitGroupDetail,
  UnitGroupMemberInput,
  UnitOfMeasure,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  DENY_ACTIONS,
  DENY_LABELS,
  DIMENSIONS,
  dimensionLabel,
  draftToConvertInput,
  draftToGroupConversionInput,
  draftToGroupInput,
  emptyConvertDraft,
  emptyGroupConversionDraft,
  emptyGroupDraft,
  groupCodeProblem,
  groupDraft,
  groupNameProblem,
  linkableUnits,
  quantityProblem,
  quantityText,
  ratioProblem,
  validateConvert,
  validateGroup,
  validateGroupConversion,
  windowProblem,
  windowText,
} from './unit-group-model';

/**
 * 3.3.3.1 计量单位前端模型单测。
 *
 * <p>重点盯两处：**拒绝原因码不能在前端被合并**（合并会让「量纲不匹配」和
 * 「不在同一个组」都变成同一句，运营去错地方找），以及**校验必须与服务层同口径**
 * （两边规则漂移的症状是「表单过了、接口 400」）。
 */

const unit = ({ code, ...over }: Partial<UnitOfMeasure> & { code: string }): UnitOfMeasure => ({
  id: code,
  code,
  name: over.name ?? code,
  dimension: over.dimension ?? 'WEIGHT',
  dimensionLabel: dimensionLabel(over.dimension ?? 'WEIGHT'),
  scale: over.scale ?? 0,
  allowPurchase: true,
  allowSale: true,
  allowStorage: true,
  enabled: over.enabled ?? true,
  conversionCount: 0,
  version: 0,
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: '2026-06-01T00:00:00Z',
  ...over,
});

/** 草稿里的成员不塞 null（契约的 `| null` 只来自 Jackson 数组元素，见 GroupDraft 的注释）。 */
const member = (unitCode: string, position: number): NonNullable<UnitGroupMemberInput> => ({
  unitCode,
  position,
});

describe('单位组编码与名称', () => {
  it('接受中文编码（运营就是这么叫的）', () => {
    expect(groupCodeProblem('重量组')).toBeNull();
    expect(groupCodeProblem('WEIGHT_GROUP')).toBeNull();
    expect(groupCodeProblem('重量组-1')).toBeNull();
  });

  it('拒绝空白、非法字符与超长', () => {
    expect(groupCodeProblem('')).toContain('不能为空');
    expect(groupCodeProblem('   ')).toContain('不能为空');
    expect(groupCodeProblem('重量 组')).toContain('只能使用');
    expect(groupCodeProblem('重量组!')).toContain('只能使用');
    expect(groupCodeProblem('重'.repeat(33))).toContain('只能使用');
    expect(groupNameProblem('')).toContain('不能为空');
    expect(groupNameProblem('重'.repeat(101))).toContain('最多 100');
  });
});

describe('换算比例与数量校验（与服务层 UnitGroupRules 同一口径）', () => {
  it('接受十进制，拒绝科学计数法与 0', () => {
    expect(ratioProblem('1000')).toBeNull();
    expect(ratioProblem('0.000001')).toBeNull();
    // 科学计数法会被 Number 变成 1e-6 再发出去，服务层会当成可疑的比例。
    expect(ratioProblem('1e3')).toContain('不支持科学计数法');
    expect(ratioProblem('0')).toContain('必须大于 0');
    expect(ratioProblem('0.000000')).toContain('必须大于 0');
    expect(ratioProblem('')).toContain('不能为空');
  });

  it('比例最多 6 位小数、整数部分最多 13 位（numeric(19,6)）', () => {
    expect(ratioProblem('1.1234567')).toContain('最多 6 位小数');
    expect(ratioProblem('12345678901234')).toContain('最多 13 位');
    expect(ratioProblem('1234567890123')).toBeNull();
  });

  it('数量校验与比例一致但不共用文案', () => {
    expect(quantityProblem('2.5')).toBeNull();
    expect(quantityProblem('0')).toContain('必须大于 0');
    expect(quantityProblem('1.1234567')).toContain('最多 6 位小数');
  });
});

describe('生效区间校验', () => {
  it('留空截止日表示当前仍生效', () => {
    expect(windowProblem('2026-01-01', '')).toBeNull();
    expect(windowProblem('2026-01-01', '2026-03-31')).toBeNull();
    expect(windowProblem('2026-01-01', '2026-03-31')).toBeNull();
  });

  it('拒绝倒挂区间与错格式', () => {
    expect(windowProblem('2026-03-31', '2026-01-01')).toContain('不能早于');
    expect(windowProblem('', '')).toContain('不能为空');
    expect(windowProblem('2026/01/01', '')).toContain('yyyy-MM-dd');
    expect(windowProblem('2026-01-01', '2026-1-1')).toContain('yyyy-MM-dd');
  });
});

describe('拒绝原因码：七条都在，且各有各的处理动作', () => {
  it('原因码与后端 UnitGroupRules.DENY_CODES 一一对应', () => {
    // 按码字符串排序时 NOT_SAME_GROUP 在 NO_EFFECTIVE_VERSION 之前：
    // 第 3 个字符 'T'(0x54) < '_'(0x5F)。
    expect(Object.keys(DENY_LABELS).sort()).toEqual([
      'DIMENSION_MISMATCH',
      'GROUP_DISABLED',
      'NOT_SAME_GROUP',
      'NO_EFFECTIVE_VERSION',
      'SELF_CONVERSION',
      'UNIT_DISABLED',
      'UNIT_UNREGISTERED',
    ]);
    // 每条原因都要有处理动作（"无需换算"除外——它不需要任何动作）。
    for (const code of Object.keys(DENY_LABELS)) {
      if (code === 'SELF_CONVERSION') continue;
      expect(DENY_ACTIONS[code]).toBeTruthy();
    }
    // 量纲不匹配与不在同组是两种不同的事，文案不能相同。
    expect(DENY_ACTIONS.DIMENSION_MISMATCH).not.toBe(DENY_ACTIONS.NOT_SAME_GROUP);
  });
});

describe('量纲词表与候选过滤', () => {
  it('量纲词表与 V8 的 CHECK 一致', () => {
    expect([...DIMENSIONS].sort()).toEqual(['COUNT', 'LENGTH', 'PACKAGE', 'VOLUME', 'WEIGHT']);
    expect(dimensionLabel('WEIGHT')).toBe('重量');
    expect(dimensionLabel(null)).toBe('未知');
  });

  it('候选成员只列同量纲且启用的单位', () => {
    const units = [
      unit({ code: '克', dimension: 'WEIGHT' }),
      unit({ code: '千克', dimension: 'WEIGHT' }),
      unit({ code: '件', dimension: 'COUNT' }),
      unit({ code: '磅', dimension: 'WEIGHT', enabled: false }),
    ];
    // 「件」不该出现在重量组的候选里：「量纲不匹配」要在候选阶段就看得见，
    // 而不是提交后才读一段错误消息再回头找。
    expect(linkableUnits(units, 'WEIGHT').map((u) => u.code)).toEqual(['克', '千克']);
    expect(linkableUnits(units, 'COUNT').map((u) => u.code)).toEqual(['件']);
    expect(linkableUnits(units, 'LENGTH')).toEqual([]);
  });
});

describe('单位组表单校验', () => {
  it('编辑时不校验编码（后端已规定建后不可改）', () => {
    const draft = { ...emptyGroupDraft(), name: '重量组', baseUnitCode: '克', members: [member('克', 0)] };
    // 新建时编码为空要报错。
    expect(validateGroup(draft, true)).toContain('单位组编码不能为空');
    // 编辑态编码留空也不报——它在表单里是只读的。
    expect(validateGroup(draft, false)).not.toContain('单位组编码不能为空');
  });

  it('基准单位必须在成员里', () => {
    const draft = {
      ...emptyGroupDraft(),
      code: '重量组',
      name: '重量组',
      baseUnitCode: '千克',
      members: [member('克', 0)],
    };
    // toContain 对数组是**精确匹配**一个元素，不是子串匹配——所以用 join 后再判包含。
    expect(validateGroup(draft, true).join('；')).toContain('必须在本组成员里');
  });

  it('成员重复与空成员都被拒', () => {
    const duplicated = {
      ...emptyGroupDraft(),
      code: '重量组',
      name: '重量组',
      baseUnitCode: '克',
      members: [member('克', 0), member('克', 1)],
    };
    expect(validateGroup(duplicated, true)).toContain('成员单位重复：克');

    const empty = { ...emptyGroupDraft(), code: '重量组', name: '重量组' };
    expect(validateGroup(empty, true)).toContain('至少要有一个成员单位');
  });

  it('草稿转输入会 trim 并重排 position', () => {
    const draft = {
      ...emptyGroupDraft(),
      code: ' 重量组 ',
      name: ' 重量组 ',
      note: ' 备注 ',
      baseUnitCode: ' 克 ',
      members: [member('千克', 5), member(' 克 ', 9)],
    };
    const input = draftToGroupInput(draft);
    expect(input.code).toBe('重量组');
    expect(input.name).toBe('重量组');
    expect(input.note).toBe('备注');
    expect(input.baseUnitCode).toBe('克');
    expect(input.members).toEqual([
      { unitCode: '千克', position: 5 },
      { unitCode: '克', position: 9 },
    ]);
  });
});

describe('从详情回填草稿：成员必须带 position', () => {
  it('整体替换语义下漏掉 position 会让顺序错乱', () => {
    const detail: UnitGroupDetail = {
      group: {
        id: 'g1',
        code: '重量组',
        name: '重量组',
        dimension: 'WEIGHT',
        dimensionLabel: '重量',
        baseUnitCode: '克',
        baseUnitName: '克',
        memberCount: 2,
        conversionVersionCount: 1,
        openConversionCount: 1,
        allMembersConvertible: true,
        note: '',
        enabled: true,
        version: 3,
        createdAt: '2026-06-01T00:00:00Z',
        updatedAt: '2026-06-01T00:00:00Z',
      },
      asOf: '2026-06-01',
      members: [
        {
          unitCode: '克',
          unitName: '克',
          scale: 0,
          position: 0,
          base: true,
          unitEnabled: true,
          currentQuantityPerBase: 1,
          currentEffectiveFrom: null,
          currentEffectiveTo: null,
          versionCount: 0,
        },
        {
          unitCode: '千克',
          unitName: '千克',
          scale: 0,
          position: 1,
          base: false,
          unitEnabled: true,
          currentQuantityPerBase: 1000,
          currentEffectiveFrom: '2024-01-01',
          currentEffectiveTo: null,
          versionCount: 1,
        },
      ],
      conversions: [],
      conversionVersionCount: 1,
    };
    const draft = groupDraft(detail);
    expect(draft.version).toBe(3);
    expect(draft.baseUnitCode).toBe('克');
    expect(draft.members.map((m) => [m.unitCode, m.position])).toEqual([
      ['克', 0],
      ['千克', 1],
    ]);
  });
});

describe('组内换算版本表单', () => {
  it('基准单位不需要换算版本（比例恒为 1）', () => {
    const problems = validateGroupConversion(
      { unitCode: '克', quantityPerBase: '1', effectiveFrom: '2026-01-01', effectiveTo: '', note: '' },
      '克',
    );
    expect(problems).toContain('组基础单位的比例恒为 1，不需要换算版本');
  });

  it('非法比例与倒挂区间都被拒', () => {
    expect(
      validateGroupConversion(
        { unitCode: '千克', quantityPerBase: '0', effectiveFrom: '2026-01-01', effectiveTo: '', note: '' },
        '克',
      ),
    ).toContain('换算比例必须大于 0');
    expect(
      validateGroupConversion(
        {
          unitCode: '千克',
          quantityPerBase: '1000',
          effectiveFrom: '2026-03-31',
          effectiveTo: '2026-01-01',
          note: '',
        },
        '克',
      ),
    ).toContain('生效截止日不能早于生效起始日');
  });

  it('草稿转输入把空截止日变成 null', () => {
    const input = draftToGroupConversionInput({
      unitCode: ' 千克 ',
      quantityPerBase: '1000',
      effectiveFrom: '2026-01-01',
      effectiveTo: '',
      note: ' 装箱比 ',
    });
    expect(input).toEqual({
      unitCode: '千克',
      quantityPerBase: 1000,
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
      note: '装箱比',
    });
  });

  it('空草稿的默认起始日是今天', () => {
    const draft = emptyGroupConversionDraft('克');
    expect(draft.effectiveFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(draft.effectiveTo).toBe('');
  });
});

describe('换算试算表单', () => {
  it('空表单给出可执行的问题清单', () => {
    const problems = validateConvert(emptyConvertDraft());
    expect(problems).toContain('请选择源单位');
    expect(problems).toContain('请选择目标单位');
  });

  it('基准日格式错要提示', () => {
    expect(
      validateConvert({ fromUnitCode: '千克', toUnitCode: '克', quantity: '1', asOf: '2026/1/1' }),
    ).toContain('基准日格式需为 yyyy-MM-dd');
  });

  it('留空的基准日变成 null（后端取今天）', () => {
    const input = draftToConvertInput({
      fromUnitCode: ' 千克 ',
      toUnitCode: ' 克 ',
      quantity: '2.5',
      asOf: '',
    });
    expect(input).toEqual({ fromUnitCode: '千克', toUnitCode: '克', quantity: 2.5, asOf: null });
  });
});

describe('展示口径', () => {
  it('缺失值画成「—」而不是 0（治理缺口不能画成 0）', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(undefined)).toBe('—');
    expect(quantityText('')).toBe('—');
  });

  it('按 scale 显示并钳到 0~6', () => {
    expect(quantityText(12.3456, 2)).toBe('12.35');
    expect(quantityText(12.3456, 0)).toBe('12');
    expect(quantityText(12.3456, 99)).toBe('12.345600');
  });

  it('开放区间显示「至今」', () => {
    expect(windowText('2026-01-01', null)).toBe('2026-01-01 ~ 至今');
    expect(windowText('2026-01-01', '2026-03-31')).toBe('2026-01-01 ~ 2026-03-31');
    expect(windowText(null, null)).toBe('—');
  });
});

describe('换算结果面板的两种形态', () => {
  it('成功时给出算式', () => {
    const result: UnitGroupConvertResult = {
      denied: false,
      denyCode: null,
      denyMessage: null,
      fromUnitCode: '千克',
      fromUnitName: '千克',
      toUnitCode: '克',
      toUnitName: '克',
      quantity: 2,
      convertedQuantity: 2000,
      fromDimension: 'WEIGHT',
      toDimension: 'WEIGHT',
      groupId: 'g1',
      groupCode: '重量组',
      groupName: '重量组',
      baseUnitCode: '克',
      fromQuantityPerBase: 1000,
      toQuantityPerBase: 1,
      asOf: '2026-06-01',
      note: '1 千克 = 1000 克',
    };
    expect(result.denied).toBe(false);
    expect(result.convertedQuantity).toBe(2000);
  });

  it('被拒时原因码与消息都带，不是一句「换算失败」', () => {
    const result: UnitGroupConvertResult = {
      denied: true,
      denyCode: 'DIMENSION_MISMATCH',
      denyMessage: '量纲不匹配：「件」属于数量，「千克」属于重量，不同量纲的单位之间不允许换算',
      fromUnitCode: '件',
      fromUnitName: '件',
      toUnitCode: '千克',
      toUnitName: '千克',
      quantity: 1,
      convertedQuantity: null,
      fromDimension: 'COUNT',
      toDimension: 'WEIGHT',
      groupId: null,
      groupCode: null,
      groupName: null,
      baseUnitCode: null,
      fromQuantityPerBase: null,
      toQuantityPerBase: null,
      asOf: '2026-06-01',
      note: '蓝图 3.3.3.1 的关系约束',
    };
    expect(result.denied).toBe(true);
    expect(DENY_LABELS[result.denyCode ?? '']).toBe('量纲不匹配');
    expect(DENY_ACTIONS[result.denyCode ?? '']).toContain('同量纲');
  });
});