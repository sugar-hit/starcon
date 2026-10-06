import { describe, expect, it } from 'vitest';
import type {
  SkuCodeBatchResult,
  SkuCodeRule,
  SkuCodeRuleInput,
  SkuIdentifier,
  SkuIdentifierImportResult,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  barcodeProblem,
  batchSummary,
  draftToRuleInput,
  emptyRuleDraft,
  expectedCheckDigit,
  importSummary,
  outcomeLabels,
  parseImportText,
  primaryIdentifier,
  ruleDraft,
  schemes,
  sourceLabels,
  validateRule,
  type RuleDraft,
} from './sku-code-model';

function rule(patch: Partial<SkuCodeRule> = {}): SkuCodeRule {
  return {
    id: 'r1',
    code: 'SKU-RULE',
    name: '默认编码规则',
    description: '',
    scope: 'ALL',
    scopeLabel: '全部 SKU',
    spuId: null,
    spuCode: null,
    prefix: 'SKU-',
    suffix: '',
    seqWidth: 6,
    startFrom: 1,
    nextValue: 1,
    sampleCode: 'SKU-000001',
    enabled: true,
    version: 0,
    updatedAt: '2026-10-07T00:00:00Z',
    ...patch,
  };
}

function identifier(patch: Partial<SkuIdentifier> = {}): SkuIdentifier {
  return {
    id: 'i1',
    scheme: 'EAN13',
    schemeLabel: 'EAN-13',
    value: '4006381333931',
    isPrimary: false,
    source: 'MANUAL',
    occurredAt: '2026-10-07T00:00:00Z',
    createdAt: '2026-10-07T00:00:00Z',
    ...patch,
  };
}

describe('barcodeProblem 与后端同口径', () => {
  it('接受各 scheme 的合法样例', () => {
    expect(barcodeProblem('EAN8', '96385074')).toBeNull();
    expect(barcodeProblem('EAN13', '4006381333931')).toBeNull();
    expect(barcodeProblem('UPCA', '036000291452')).toBeNull();
    // 自定义 scheme 没有固定位数与校验位，不能硬套 GTIN 规则
    expect(barcodeProblem('CUSTOM', 'SUPPLIER-A-9931')).toBeNull();
    expect(barcodeProblem('custom', '123')).toBeNull();
  });

  it('拒绝位数不对、含非数字、校验位错', () => {
    expect(barcodeProblem('EAN13', '400638133393')).toContain('13 位');
    expect(barcodeProblem('EAN13', '40063813339X1')).toContain('只能由数字组成');
    expect(barcodeProblem('EAN13', '4006381333930')).toContain('校验位不正确');
  });

  it('拒绝未知 scheme 并明确指出 seller_sku 不在内', () => {
    expect(barcodeProblem('SELLER_SKU', 'ABC-1')).toContain('不支持的条码 scheme');
    expect(schemes).not.toContain('SELLER_SKU' as never);
  });

  it('拒绝空值与超长值', () => {
    expect(barcodeProblem('EAN13', '')).toContain('不能为空');
    expect(barcodeProblem('EAN13', '   ')).toContain('不能为空');
    expect(barcodeProblem('CUSTOM', 'x'.repeat(129))).toContain('128');
  });
});

describe('expectedCheckDigit 是 GS1 mod-10', () => {
  it('与手工核对过的样例一致', () => {
    // 入参是数据位（不含校验位），右起权重 3,1,3,1…
    expect(expectedCheckDigit('9638507')).toBe('4');
    expect(expectedCheckDigit('400638133393')).toBe('1');
    expect(expectedCheckDigit('03600029145')).toBe('2');
  });

  it('对每个 scheme 都能生成一条自洽的条码', () => {
    const widths: Record<string, number> = { EAN8: 7, UPCE: 7, UPCA: 11, EAN13: 12, GTIN14: 13 };
    for (const scheme of schemes.filter((s) => widths[s])) {
      const body = '1234567890'.repeat(2).slice(0, widths[scheme]);
      const full = body + expectedCheckDigit(body);
      expect(barcodeProblem(scheme, full), scheme).toBeNull();
    }
  });
});

describe('规则表单', () => {
  it('保存前就挡住展开后越界——不能等批量生成之后才发现', () => {
    const draft: RuleDraft = {
      ...emptyRuleDraft(),
      code: 'TOOLONG',
      name: '超长',
      prefix: 'A'.repeat(32),
      suffix: 'B'.repeat(32),
      seqWidth: '12',
    };
    const errors = validateRule(draft);
    expect(errors.some((e) => e.includes('76') && e.includes('64'))).toBe(true);
    // 刚好 64 放得下
    expect(
      validateRule({ ...draft, prefix: 'A'.repeat(32), suffix: 'B'.repeat(20) }).some((e) =>
        e.includes('超过 64'),
      ),
    ).toBe(false);
  });

  it('校验编码、前后缀字符集、序号宽度与起始序号', () => {
    expect(validateRule({ ...emptyRuleDraft(), code: 'a b', name: 'x' }).join()).toContain(
      '规则编码',
    );
    expect(validateRule({ ...emptyRuleDraft(), code: 'R1', name: 'x', prefix: 'SKU 1' }).join()).toContain(
      '编码前缀',
    );
    expect(
      validateRule({ ...emptyRuleDraft(), code: 'R1', name: 'x', suffix: '商品' }).join(),
    ).toContain('编码后缀');
    expect(
      validateRule({ ...emptyRuleDraft(), code: 'R1', name: 'x', seqWidth: '13' }).join(),
    ).toContain('序号宽度');
    expect(
      validateRule({ ...emptyRuleDraft(), code: 'R1', name: 'x', startFrom: '0' }).join(),
    ).toContain('起始序号');
    expect(validateRule({ ...emptyRuleDraft(), code: 'R1' }).join()).toContain('规则名称');
  });

  it('限定商品族必须选 SPU', () => {
    expect(validateRule({ ...emptyRuleDraft(), code: 'R', name: 'n', scope: 'SPU' }).join()).toContain(
      '必须指定 SPU',
    );
  });

  it('草稿与请求体互转，且 version 原样带上', () => {
    const source = rule({ scope: 'SPU', spuId: 'spu-1', spuCode: 'SPU-1', version: 7 });
    const draft = ruleDraft(source);
    expect(draft.scope).toBe('SPU');
    expect(draft.spuId).toBe('spu-1');
    const input: SkuCodeRuleInput = draftToRuleInput(draft);
    expect(input.version).toBe(7);
    expect(input.spuId).toBe('spu-1');
    expect(input.code).toBe('SKU-RULE');
    // 前后缀规范化成大写，与后端一致
    expect(draftToRuleInput({ ...draft, prefix: 'sku-', suffix: 'x' })).toMatchObject({
      prefix: 'SKU-',
      suffix: 'X',
    });
    // ALL 范围不许带 spuId，否则唯一约束形同虚设
    expect(draftToRuleInput({ ...draft, scope: 'ALL', spuId: 'spu-1' }).spuId).toBeNull();
  });
});

describe('批量结果摘要', () => {
  it('生效/跳过/拒绝三个计数一起说出来', () => {
    const result = {
      batchId: 'b1',
      action: 'GENERATE',
      ruleCode: 'R',
      dryRun: false,
      requestedCount: 5,
      matchedCount: 4,
      appliedCount: 2,
      skippedCount: 1,
      rejectedCount: 1,
      items: [],
    } as SkuCodeBatchResult;
    expect(batchSummary(result)).toBe('生效 2 条 · 跳过 1 条 · 拒绝 1 条');
    // 没有跳过/拒绝时不该出现「跳过 0 条」这种噪声
    expect(batchSummary({ ...result, skippedCount: 0, rejectedCount: 0 })).toBe('生效 2 条');
  });

  it('导入摘要带上拒绝条数', () => {
    const result = {
      batchId: 'b1',
      dryRun: false,
      requestedCount: 7,
      appliedCount: 2,
      rejectedCount: 5,
      items: [],
    } as SkuIdentifierImportResult;
    expect(importSummary(result)).toBe('导入 2 条 · 拒绝 5 条');
  });

  it('三种 outcome 都有中文标签', () => {
    for (const key of ['APPLIED', 'SKIPPED', 'REJECTED']) {
      expect(outcomeLabels[key]).toBeTruthy();
    }
    expect(Object.keys(outcomeLabels)).toHaveLength(3);
  });

  it('条码来源都有中文标签', () => {
    expect(sourceLabels.MANUAL).toBe('手工输入');
    expect(sourceLabels.SCAN).toBe('扫码录入');
    expect(sourceLabels.IMPORT).toBe('批量导入');
  });
});

describe('主条码', () => {
  it('取 isPrimary 的那一个，没有则返回 null', () => {
    const list = [
      identifier({ id: 'a', scheme: 'EAN13' }),
      identifier({ id: 'b', scheme: 'UPCA', isPrimary: true }),
    ];
    expect(primaryIdentifier(list)?.id).toBe('b');
    expect(primaryIdentifier([identifier()])).toBeNull();
    expect(primaryIdentifier([])).toBeNull();
  });
});

describe('parseImportText', () => {
  const resolve = (code: string) => (code === 'SKU-1' ? 'sku-uuid-1' : null);

  it('解析制表符/逗号/多空格三种分隔，并识别主条码标记', () => {
    const { rows, problems } = parseImportText(
      [
        'SKU-1\tEAN13\t4006381333931',
        'SKU-1, UPCA, 036000291452, 是',
        'SKU-1    GTIN14    00614141001217',
      ].join('\n'),
      resolve,
    );
    expect(problems).toHaveLength(0);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toMatchObject({ skuId: 'sku-uuid-1', scheme: 'EAN13', isPrimary: false });
    expect(rows[1]?.isPrimary).toBe(true);
    expect(rows[2]?.scheme).toBe('GTIN14');
  });

  it('跳过空行与注释行', () => {
    const { rows } = parseImportText('# 供应商导出\n\nSKU-1\tEAN13\t4006381333931\n', resolve);
    expect(rows).toHaveLength(1);
  });

  it('格式错的行留在 problems 里并带行号，而不是被静默丢掉', () => {
    const { rows, problems } = parseImportText(
      ['SKU-1\tEAN13\t4006381333931', 'SKU-1\tEAN13', 'NOPE\tEAN13\t4006381333931'].join('\n'),
      resolve,
    );
    expect(rows).toHaveLength(1);
    expect(problems).toHaveLength(2);
    expect(problems[0]?.line).toBe(2);
    expect(problems[0]?.reason).toContain('三列');
    expect(problems[1]?.reason).toContain('找不到 SKU 编码 NOPE');
  });

  it('校验位错的行在提交前就能被发现', () => {
    const { rows, problems } = parseImportText('SKU-1\tEAN13\t4006381333930', resolve);
    expect(rows).toHaveLength(0);
    expect(problems[0]?.reason).toContain('校验位不正确');
  });
});