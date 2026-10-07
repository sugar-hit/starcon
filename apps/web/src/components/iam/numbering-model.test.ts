import { describe, expect, it } from 'vitest';
import {
  DATE_FORMATS,
  SEGMENT_TYPE_LABELS,
  type RuleRow,
  type RuleSegment,
  composeSample,
  detectsNestingCycle,
  effectiveSummary,
  findConflicts,
  formatDate,
  groupByEntity,
  intervalsOverlap,
  resetKey,
  segmentSummary,
  validateRule,
  validateSegment,
} from './numbering-model';

const prefix = (value: string): RuleSegment => ({ segmentType: 'PREFIX', literalText: value });
const suffix = (value: string): RuleSegment => ({ segmentType: 'SUFFIX', literalText: value });
const dateSeg = (format: string): RuleSegment => ({ segmentType: 'DATE', dateFormat: format });
const seq = (width: number, start: number, reset: string, scope: string): RuleSegment => ({
  segmentType: 'SEQUENCE',
  sequenceWidth: width,
  sequenceStart: start,
  sequenceReset: reset,
  sequenceScope: scope,
});
const nested = (childRuleId: string): RuleSegment => ({ segmentType: 'NESTED', childRuleId });

const rule = (over: Partial<RuleRow> = {}): RuleRow => ({
  id: 'r1',
  entityCode: 'ORDER',
  ruleName: '销售订单编号',
  ruleKind: 'PRIMARY',
  status: 'ACTIVE',
  effectiveFrom: '2026-01-01T00:00:00Z',
  effectiveTo: null,
  ...over,
});

const componentMap = (...rows: RuleRow[]) =>
  new Map(rows.filter((r) => r.ruleKind === 'COMPONENT').map((r) => [r.id, r]));

// ------------------------------------------------------------------
// 重置分桶：与后端 resetKey 同口径
// ------------------------------------------------------------------

describe('resetKey', () => {
  it('四种口径各自分桶，NONE 恒为 ALL', () => {
    expect(resetKey('NONE', '2026-10-07T10:00:00Z')).toBe('ALL');
    expect(resetKey('DAILY', '2026-10-07T10:00:00Z')).toBe('2026-10-07');
    expect(resetKey('MONTHLY', '2026-10-07T10:00:00Z')).toBe('2026-10');
    expect(resetKey('YEARLY', '2026-10-07T10:00:00Z')).toBe('2026');
  });

  it('同一天不同时刻落同一个桶（分桶看日期不看时刻）', () => {
    expect(resetKey('DAILY', '2026-10-07T00:00:00Z')).toBe(
      resetKey('DAILY', '2026-10-07T23:59:00Z'),
    );
  });

  it('跨日换桶，跨月换桶，跨年换桶', () => {
    expect(resetKey('DAILY', '2026-10-07T10:00:00Z')).not.toBe(
      resetKey('DAILY', '2026-10-08T10:00:00Z'),
    );
    expect(resetKey('MONTHLY', '2026-10-07T10:00:00Z')).not.toBe(
      resetKey('MONTHLY', '2026-11-01T10:00:00Z'),
    );
    expect(resetKey('YEARLY', '2026-10-07T10:00:00Z')).not.toBe(
      resetKey('YEARLY', '2027-01-01T10:00:00Z'),
    );
  });

  it('分桶用的是业务日期而不是今天：补录旧单据要落旧桶', () => {
    // 2026-03 的补录单据落 3 月桶，不会插进当前月份把当月序号顶开
    expect(resetKey('MONTHLY', '2026-03-15T00:00:00Z')).toBe('2026-03');
  });
});

// ------------------------------------------------------------------
// 生效区间冲突
// ------------------------------------------------------------------

describe('intervalsOverlap', () => {
  it('完全包含、部分交叠、包含起点都算冲突', () => {
    expect(intervalsOverlap('2026-01-01', '2026-12-31', '2026-03-01', '2026-04-01')).toBe(true);
    expect(intervalsOverlap('2026-03-01', '2026-04-01', '2026-01-01', '2026-12-31')).toBe(true);
    expect(intervalsOverlap('2025-01-01', '2026-02-01', '2026-01-01', '2026-12-31')).toBe(true);
  });

  it('相邻不算冲突：前一条到点失效、后一条接上生效是正常配置', () => {
    expect(intervalsOverlap('2026-01-01', '2026-07-01', '2026-07-01', '2027-01-01')).toBe(false);
    expect(intervalsOverlap('2026-07-01', '2027-01-01', '2026-01-01', '2026-07-01')).toBe(false);
  });

  it('null 结束时间当作长期有效，会与任何后接区间冲突', () => {
    expect(intervalsOverlap('2026-01-01', null, '2030-01-01', null)).toBe(true);
    expect(intervalsOverlap('2030-01-01', null, '2026-01-01', '2029-12-31')).toBe(false);
  });

  it('完全不相交不算冲突', () => {
    expect(intervalsOverlap('2020-01-01', '2021-01-01', '2026-01-01', '2027-01-01')).toBe(false);
  });
});

describe('findConflicts', () => {
  const base = rule({
    id: 'a',
    ruleName: '已有规则',
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveTo: '2026-07-01T00:00:00Z',
  });

  it('同实体主规则区间交叠时报出对手方', () => {
    const clash = findConflicts([base], {
      entityCode: 'ORDER',
      ruleKind: 'PRIMARY',
      status: 'ACTIVE',
      effectiveFrom: '2026-03-01T00:00:00Z',
      effectiveTo: '2026-04-01T00:00:00Z',
    });
    expect(clash).not.toBeNull();
    expect(clash && clash[0].ruleName).toBe('已有规则');
  });

  it('不同实体互不干扰', () => {
    expect(
      findConflicts([base], {
        entityCode: 'PURCHASE_ORDER',
        ruleKind: 'PRIMARY',
        status: 'ACTIVE',
        effectiveFrom: '2026-03-01T00:00:00Z',
        effectiveTo: '2026-04-01T00:00:00Z',
      }),
    ).toBeNull();
  });

  it('子规则不参与判定：它只被嵌套引用，不直接给实体发号', () => {
    expect(
      findConflicts([base], {
        entityCode: 'ORDER',
        ruleKind: 'COMPONENT',
        status: 'ACTIVE',
        effectiveFrom: '2026-03-01T00:00:00Z',
        effectiveTo: '2026-04-01T00:00:00Z',
      }),
    ).toBeNull();
  });

  it('停用状态不参与判定：否则"停用一条再启用一条"永远放行不了', () => {
    expect(
      findConflicts([base], {
        entityCode: 'ORDER',
        ruleKind: 'PRIMARY',
        status: 'DISABLED',
        effectiveFrom: '2026-03-01T00:00:00Z',
        effectiveTo: '2026-04-01T00:00:00Z',
      }),
    ).toBeNull();
    const disabledExisting = rule({ status: 'DISABLED' });
    expect(
      findConflicts([disabledExisting], {
        entityCode: 'ORDER',
        ruleKind: 'PRIMARY',
        status: 'ACTIVE',
        effectiveFrom: '2026-03-01T00:00:00Z',
        effectiveTo: '2026-04-01T00:00:00Z',
      }),
    ).toBeNull();
  });

  it('更新自己时不把自己算成冲突', () => {
    expect(
      findConflicts([base], {
        id: 'a',
        entityCode: 'ORDER',
        ruleKind: 'PRIMARY',
        status: 'ACTIVE',
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: '2026-07-01T00:00:00Z',
      }),
    ).toBeNull();
  });
});

// ------------------------------------------------------------------
// 段形状校验
// ------------------------------------------------------------------

describe('validateSegment', () => {
  it('文本段必须有内容', () => {
    expect(validateSegment(prefix('SO'), [])).toBe('');
    expect(validateSegment({ segmentType: 'PREFIX', literalText: '  ' }, [])).toContain(
      '需要填写文本',
    );
    expect(validateSegment({ segmentType: 'SUFFIX' }, [])).toContain('需要填写文本');
  });

  it('日期段必须在白名单里', () => {
    for (const format of DATE_FORMATS) expect(validateSegment(dateSeg(format), [])).toBe('');
    expect(validateSegment(dateSeg('yyyy/MM/dd'), [])).toContain('不支持的日期格式');
    expect(validateSegment({ segmentType: 'DATE' }, [])).toContain('日期格式');
  });

  it('序号段的宽度、起始值、重置口径、作用域都要配全', () => {
    expect(validateSegment(seq(4, 1, 'DAILY', 'ENTITY'), [])).toBe('');
    expect(validateSegment({ segmentType: 'SEQUENCE' }, [])).toContain('序号宽度');
    expect(validateSegment(seq(0, 1, 'DAILY', 'ENTITY'), [])).toContain('1 到 12');
    expect(validateSegment(seq(13, 1, 'DAILY', 'ENTITY'), [])).toContain('1 到 12');
    expect(validateSegment({ segmentType: 'SEQUENCE', sequenceWidth: 4 }, [])).toContain(
      '起始序号',
    );
    expect(
      validateSegment({ segmentType: 'SEQUENCE', sequenceWidth: 4, sequenceStart: 1 }, []),
    ).toContain('重置口径');
    expect(
      validateSegment(
        { segmentType: 'SEQUENCE', sequenceWidth: 4, sequenceStart: 1, sequenceReset: 'DAILY' },
        [],
      ),
    ).toContain('计数作用域');
    expect(validateSegment(seq(4, -1, 'DAILY', 'ENTITY'), [])).toContain('起始序号');
  });

  it('嵌套段只能指向已知的子规则', () => {
    expect(validateSegment(nested('c1'), ['c1'])).toBe('');
    expect(validateSegment(nested('c1'), [])).toContain('可复用子规则');
    expect(validateSegment({ segmentType: 'NESTED' }, ['c1'])).toContain('子规则');
  });
});

// ------------------------------------------------------------------
// 嵌套成环
// ------------------------------------------------------------------

describe('detectsNestingCycle', () => {
  it('直接自嵌被识别', () => {
    const components = componentMap(rule({ id: 'c1', ruleKind: 'COMPONENT' }));
    expect(detectsNestingCycle([nested('c1')], components, 'c1')).toBe(true);
  });

  it('间接成环（A→B→A）被识别', () => {
    const components = componentMap(
      rule({ id: 'a', ruleKind: 'COMPONENT', segments: [nested('b')] }),
      rule({ id: 'b', ruleKind: 'COMPONENT', segments: [nested('a')] }),
    );
    expect(detectsNestingCycle([nested('a')], components, 'c1')).toBe(true);
  });

  it('合法嵌套（外层→子→孙）不被误判', () => {
    const components = componentMap(
      rule({ id: 'inner', ruleKind: 'COMPONENT', segments: [prefix('X')] }),
      rule({ id: 'mid', ruleKind: 'COMPONENT', segments: [nested('inner')] }),
    );
    expect(detectsNestingCycle([nested('mid')], components)).toBe(false);
  });

  it('指向不存在的规则不算环（那是另一类错误）', () => {
    expect(detectsNestingCycle([nested('ghost')], componentMap(), 'c1')).toBe(false);
  });
});

// ------------------------------------------------------------------
// 整条规则的预校验
// ------------------------------------------------------------------

describe('validateRule', () => {
  const valid = {
    entityCode: 'ORDER',
    ruleName: '销售订单编号',
    ruleKind: 'PRIMARY',
    timezone: 'Asia/Shanghai',
    effectiveFrom: '2030-01-01T00:00:00Z',
    effectiveTo: null,
    segments: [prefix('SO'), dateSeg('yyyyMMdd'), seq(4, 1, 'DAILY', 'ENTITY')],
  };

  it('完整可用的规则通过校验', () => {
    expect(validateRule(valid, [], new Map())).toBe('');
  });

  it('实体、名称、段列表都不能空', () => {
    expect(validateRule({ ...valid, entityCode: '' }, [], new Map())).toContain('单据实体');
    expect(validateRule({ ...valid, ruleName: '  ' }, [], new Map())).toContain('规则名称');
    expect(validateRule({ ...valid, segments: [] }, [], new Map())).toContain('至少需要一个组成段');
  });

  it('生效区间倒置被拒', () => {
    expect(
      validateRule(
        { ...valid, effectiveFrom: '2026-07-01T00:00:00Z', effectiveTo: '2026-01-01T00:00:00Z' },
        [],
        new Map(),
      ),
    ).toContain('晚于');
  });

  it('与已有规则冲突时给出对手方名称', () => {
    const existing = [
      rule({
        id: 'a',
        ruleName: '旧版编号',
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: null,
      }),
    ];
    expect(validateRule(valid, existing, new Map())).toContain('旧版编号');
  });

  it('嵌套环在界面上先被拦下', () => {
    const components = componentMap(
      rule({ id: 'c1', ruleKind: 'COMPONENT', segments: [nested('c1')] }),
    );
    expect(
      validateRule({ ...valid, ruleKind: 'COMPONENT', segments: [nested('c1')] }, [], components),
    ).toContain('环');
  });
});

// ------------------------------------------------------------------
// 本地拼装与日期渲染
// ------------------------------------------------------------------

describe('composeSample', () => {
  it('前缀 + 日期 + 序号 + 后缀按顺序拼成', () => {
    expect(
      composeSample(
        [prefix('SO'), dateSeg('yyyyMMdd'), seq(4, 7, 'DAILY', 'ENTITY'), suffix('-US')],
        '2026-05-04T00:00:00Z',
      ),
    ).toBe('SO202605040007-US');
  });

  it('序号左补零到指定宽度', () => {
    expect(composeSample([seq(6, 42, 'NONE', 'ENTITY')], '2026-05-04T00:00:00Z')).toBe('000042');
  });

  it('序号超过宽度时原样输出，不截断', () => {
    expect(composeSample([seq(2, 12345, 'NONE', 'ENTITY')], '2026-05-04T00:00:00Z')).toBe('12345');
  });

  it('嵌套段在本地预览里留空——真实展开只能由服务端给出', () => {
    expect(composeSample([prefix('A'), nested('c1'), suffix('B')], '2026-05-04T00:00:00Z')).toBe(
      'AB',
    );
  });
});

describe('formatDate', () => {
  it('六种白名单格式都渲染正确', () => {
    expect(formatDate('2026-10-07', 'yyyyMMdd')).toBe('20261007');
    expect(formatDate('2026-10-07', 'yyyyMM')).toBe('202610');
    expect(formatDate('2026-10-07', 'yyyy')).toBe('2026');
    expect(formatDate('2026-10-07', 'yyMMdd')).toBe('261007');
    expect(formatDate('2026-10-07', 'MMdd')).toBe('1007');
    expect(formatDate('2026-10-07', 'yyyy-MM-dd')).toBe('2026-10-07');
  });

  it('未知格式返回空串而不是抛错（界面不该为一个坏配置崩掉）', () => {
    expect(formatDate('2026-10-07', 'yyyy/MM/dd')).toBe('');
  });
});

// ------------------------------------------------------------------
// 展示口径
// ------------------------------------------------------------------

describe('展示', () => {
  it('生效区间长期有效的规则不会被截断成只有一个时间', () => {
    expect(effectiveSummary({ effectiveFrom: '2026-01-01T00:00:00Z', effectiveTo: null })).toBe(
      '2026-01-01 ~ 长期有效',
    );
    expect(
      effectiveSummary({
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: '2026-07-01T00:00:00Z',
      }),
    ).toBe('2026-01-01 ~ 2026-07-01');
  });

  it('段的摘要能讲清这一段是什么', () => {
    expect(segmentSummary(prefix('SO'))).toBe('SO');
    expect(segmentSummary(dateSeg('yyyyMMdd'))).toBe('20261007');
    expect(segmentSummary(seq(4, 1, 'DAILY', 'ENTITY'))).toBe('DAILY · ENTITY');
    expect(segmentSummary(nested('c1'))).toBe('未选择子规则');
    expect(segmentSummary({ segmentType: 'NESTED', childRuleName: '日期序号片段' })).toBe(
      '日期序号片段',
    );
  });

  it('段类型都有中文标签，新增类型会显式暴露而不是显示空白', () => {
    for (const type of ['PREFIX', 'DATE', 'SEQUENCE', 'SUFFIX', 'LITERAL', 'NESTED'])
      expect(SEGMENT_TYPE_LABELS[type]).toBeTruthy();
  });

  it('按实体分组，顺序稳定', () => {
    const rows = [
      rule({ id: '1', entityCode: 'ORDER' }),
      rule({ id: '2', entityCode: 'OUTBOUND' }),
      rule({ id: '3', entityCode: 'ORDER' }),
    ];
    const groups = groupByEntity(rows);
    expect(groups.map((g) => g.entityCode)).toEqual(['ORDER', 'OUTBOUND']);
    expect(groups[0]?.rows).toHaveLength(2);
  });

  it('空列表不产生空分组', () => {
    expect(groupByEntity([])).toEqual([]);
  });
});
