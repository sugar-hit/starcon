import { describe, expect, it } from 'vitest';
import {
  BOM_STATUSES,
  MAX_COMPONENTS_PER_VERSION,
  SCRAP_MAX,
  SCRAP_SCALE,
  SCRAP_UNIT_HINT,
  VERSION_STATUSES,
  bomStatusLabel,
  bomStatusTone,
  canCloseVersion,
  canEditVersion,
  closeHint,
  closeToProblem,
  codeProblem,
  componentCountProblem,
  componentSummary,
  componentsProblem,
  effectiveLabel,
  emptyComponentDraft,
  errorText,
  instantToLocalInput,
  localInputToInstant,
  outputQuantityProblem,
  quantityProblem,
  rangeOrderProblem,
  rangeText,
  requiredText,
  scrapRateProblem,
  statusHint,
  statusLabel,
  statusTone,
  toComponentDraft,
  toComponentInput,
  usageEditableLabel,
  versionNameProblem,
  versionNoProblem,
} from './bom-model';
import { ApiError } from './api';

/**
 * 3.5.2.1 BOM 前端模型测试。
 *
 * <p>重点是三条容易在前端算错的口径：损耗率是**百分数**、需求量**不取整**、
 * 「此刻生效」**不由前端重算**。
 */
describe('损耗率是百分数（规则 R6）', () => {
  it('5 就是 5%，不是 500%', () => {
    expect(scrapRateProblem('5')).toBeNull();
    expect(scrapRateProblem('0')).toBeNull();
    expect(scrapRateProblem('0.5')).toBeNull();
    expect(scrapRateProblem('100')).toBeNull();
  });

  it('留空合法：委外加工里「不损耗」是绝大多数情况，必填只会让人填一堆 0', () => {
    expect(scrapRateProblem('')).toBeNull();
    expect(scrapRateProblem('   ')).toBeNull();
  });

  it('上界是 100%，且文案要解释为什么 100% 也不行', () => {
    expect(SCRAP_MAX).toBe(100);
    expect(scrapRateProblem('100.0001')).toContain('不能超过 100%');
    expect(scrapRateProblem('150')).toContain('100% 损耗等于这个组件一丁点都用不上');
    expect(scrapRateProblem('-1')).toContain('不能是负数');
  });

  it('小数位上限与 numeric(7,4) 一致', () => {
    expect(SCRAP_SCALE).toBe(4);
    expect(scrapRateProblem('1.23456')).toContain('最多 4 位小数');
  });

  it('单位说明写在 hint 上而不是藏起来：填错是静默的', () => {
    expect(SCRAP_UNIT_HINT).toContain('填 5 就是损耗 5%');
    expect(SCRAP_UNIT_HINT).toContain('不是 500%');
    expect(SCRAP_UNIT_HINT).toContain('无损耗填 0');
  });

  it('非数字直接拒绝，不让它落进 NaN', () => {
    expect(scrapRateProblem('abc')).toContain('不是数字');
    expect(scrapRateProblem('NaN')).toContain('不是数字');
  });
});

describe('单位用量（规则 R7 的因子）', () => {
  it('必须大于 0', () => {
    expect(quantityProblem('0')).toContain('必须大于 0');
    expect(quantityProblem('-1')).toContain('必须大于 0');
    expect(quantityProblem('')).toContain('不能为空');
  });

  it('接受小数：0.5 米面料是真实场景，int 会逼运营写成 1 件', () => {
    expect(quantityProblem('0.5')).toBeNull();
    expect(quantityProblem('2')).toBeNull();
    expect(quantityProblem('2.0')).toBeNull();
  });

  it('小数位上限与 numeric(19,6) 一致', () => {
    expect(quantityProblem('0.1234567')).toContain('最多 6 位小数');
  });

  it('非数字要报错并说明后果：按假数领料是静默的', () => {
    expect(quantityProblem('两')).toContain('不是数字');
  });
});

describe('成品数量与需求量不取整（规则 R7）', () => {
  it('成品数量必须是正整数', () => {
    expect(outputQuantityProblem('')).toContain('请填写要生产的成品数量');
    expect(outputQuantityProblem('0')).toContain('必须大于 0');
    expect(outputQuantityProblem('0.5')).toContain('必须是整数');
    expect(outputQuantityProblem('100')).toBeNull();
  });

  it('产 0 个要说出后果：清单会被误读成「什么料都不用」', () => {
    expect(outputQuantityProblem('0')).toContain('什么料都不用');
  });

  it('需求量原样展示，不四舍五入成整数', () => {
    // 4.5 不能被悄悄变成 4 或 5：取整是 3.5.2.2 与 06 的决定。
    expect(requiredText(4.5)).toBe('4.5');
    expect(requiredText(260)).toBe('260');
    expect(requiredText(3.37035)).toBe('3.37035');
  });
});

describe('组件清单', () => {
  it('规模上下限与后端一致', () => {
    expect(MAX_COMPONENTS_PER_VERSION).toBe(50);
    expect(componentCountProblem(0)).toContain('至少要有一个组件');
    expect(componentCountProblem(50)).toBeNull();
    expect(componentCountProblem(51)).toContain('最多 50 个组件');
  });

  it('空清单要说明发布时会被拒，而不是画成「0 个组件」', () => {
    expect(componentSummary([], 0)).toContain('发布时会被拒绝');
    expect(componentSummary([], null)).toContain('算不出任何需求量');
  });

  it('有组件时显示合计与最高损耗率', () => {
    const rows = [
      {
        id: '1',
        versionId: 'v',
        skuId: 's1',
        skuCode: 'A',
        skuName: '主板',
        unitQuantity: 2,
        scrapRate: 5,
        role: '',
        note: '',
      },
      {
        id: '2',
        versionId: 'v',
        skuId: 's2',
        skuCode: 'B',
        skuName: '包材',
        unitQuantity: 0.5,
        scrapRate: 0,
        role: '',
        note: '',
      },
    ];
    expect(componentSummary(rows, 2.5)).toContain('2 个组件');
    expect(componentSummary(rows, 2.5)).toContain('每件合计 2.5');
    expect(componentSummary(rows, 2.5)).toContain('最高损耗 5%');
  });

  it('全零损耗时不显示「最高损耗 0%」这种噪声', () => {
    const rows = [
      {
        id: '1',
        versionId: 'v',
        skuId: 's1',
        skuCode: 'A',
        skuName: '主板',
        unitQuantity: 1,
        scrapRate: 0,
        role: '',
        note: '',
      },
    ];
    expect(componentSummary(rows, 1)).toContain('无损耗');
  });

  it('提交前一次性校验，返回第一条问题', () => {
    expect(componentsProblem([])).toContain('至少要有一个组件');
    expect(componentsProblem([{ skuId: 'a', unitQuantity: '0', scrapRate: '' }])).toContain(
      '必须大于 0',
    );
    expect(
      componentsProblem([{ skuId: 'a', unitQuantity: '1', scrapRate: '200' }]),
    ).toContain('不能超过 100%');
    expect(
      componentsProblem([
        { skuId: 'a', unitQuantity: '1', scrapRate: '' },
        { skuId: 'a', unitQuantity: '2', scrapRate: '' },
      ]),
    ).toContain('出现了两次');
    expect(
      componentsProblem([{ skuId: '', unitQuantity: '1', scrapRate: '' }]),
    ).toContain('每个组件都要选一个 SKU');
  });

  it('草稿默认值：用量 1、损耗率留空（大多数组件不损耗）', () => {
    expect(emptyComponentDraft()).toEqual({ skuId: '', unitQuantity: '1', scrapRate: '' });
  });

  it('空损耗率提交为 null，后端存 0', () => {
    expect(toComponentInput({ skuId: 's', unitQuantity: '2', scrapRate: '' })).toEqual({
      skuId: 's',
      unitQuantity: 2,
      scrapRate: null,
      role: '',
      note: '',
    });
    expect(toComponentInput({ skuId: 's', unitQuantity: '2.5', scrapRate: '5' })).toEqual({
      skuId: 's',
      unitQuantity: 2.5,
      scrapRate: 5,
      role: '',
      note: '',
    });
  });

  it('回填已保存的行时保留用量与损耗率', () => {
    expect(
      toComponentDraft({
        id: '1',
        versionId: 'v',
        skuId: 's',
        skuCode: 'A',
        skuName: '主板',
        unitQuantity: 2,
        scrapRate: 5,
        role: '主板',
        note: '',
      }),
    ).toEqual({ skuId: 's', unitQuantity: '2', scrapRate: '5' });
  });
});

describe('生效区间（规则 R1–R5 的前端口径）', () => {
  it('半开区间：终点必须严格晚于起点', () => {
    expect(rangeOrderProblem('', '')).toContain('生效起点不能为空');
    expect(rangeOrderProblem('2026-03-01T00:00', '')).toBeNull();
    expect(rangeOrderProblem('2026-03-01T00:00', '2026-03-01T00:00')).toContain(
      '必须晚于生效起点',
    );
    expect(rangeOrderProblem('2026-03-01T06:00', '2026-03-01T00:00')).toContain(
      '必须晚于生效起点',
    );
  });

  it('展示文案要写清半开区间与「终点空=一直有效」', () => {
    expect(rangeText('2026-03-01T00:00:00Z', null)).toContain('一直有效');
    expect(rangeText('2026-03-01T00:00:00Z', '2026-03-02T00:00:00Z')).toContain(
      '终点那一瞬间归下一版',
    );
  });

  it('提前停用：终点不能落进过去，也不能往后延', () => {
    const past = new Date(Date.now() - 86_400_000).toISOString();
    expect(closeToProblem('', null)).toContain('要指定一个具体时刻');
    expect(closeToProblem(past, null)).toContain('必须落在当前时刻之后');
    const current = new Date(Date.now() + 86_400_000).toISOString();
    const later = new Date(Date.now() + 7 * 86_400_000).toISOString();
    // 签名是 (要设的终点, 现在的终点)：把终点从 current 往后延到 later 必须被拒。
    expect(closeToProblem(later, current)).toContain('只能收紧不能往后延');
    expect(closeToProblem(current, later)).toBeNull();
  });

  it('提前停用不是死路：文案要说清它只影响未来', () => {
    const hint = closeHint({ versionNo: 'v2', effectiveTo: null });
    expect(hint).toContain('v2');
    expect(hint).toContain('下一个版本接替');
    // 「只能往前」是这条纪律的核心：落进过去等于追溯改写历史。
    expect(hint).toContain('收紧只能往前');
    expect(hint).toContain('落进过去');
  });
});

describe('已发布不可改要在界面上就讲清楚（规则 R5 的唯一例外）', () => {
  it('已发布整版冻结 —— 不是「部分字段」', () => {
    expect(canEditVersion({ status: 'DRAFT' })).toBe(true);
    expect(canEditVersion({ status: 'PUBLISHED' })).toBe(false);
  });

  it('只有已发布版本需要「提前停用」', () => {
    expect(canCloseVersion({ status: 'DRAFT' })).toBe(false);
    expect(canCloseVersion({ status: 'PUBLISHED' })).toBe(true);
  });

  it('BOM 与套装不同：冻结的是「用量与损耗率」，因为 08 已按它领过料', () => {
    expect(statusHint('PUBLISHED')).toContain('08 加工单已按它领过料');
  });
});

describe('状态词表与后端 CHECK 同一口径', () => {
  it('版本状态', () => {
    expect(VERSION_STATUSES.map((s) => s.value)).toEqual(['DRAFT', 'PUBLISHED']);
    expect(statusLabel('DRAFT')).toBe('草稿');
    expect(statusLabel('PUBLISHED')).toBe('已发布');
    expect(statusTone('PUBLISHED')).toBe('success');
    expect(statusTone('DRAFT')).toBe('warning');
  });

  it('BOM 状态', () => {
    expect(BOM_STATUSES.map((s) => s.value)).toEqual(['ACTIVE', 'ARCHIVED']);
    expect(bomStatusLabel('ARCHIVED')).toBe('已归档');
    expect(bomStatusTone('ACTIVE')).toBe('success');
  });

  it('未知状态回退到原值而不是空白', () => {
    expect(statusLabel('WHATEVER')).toBe('WHATEVER');
    expect(statusTone('WHATEVER')).toBe('neutral');
  });
});

describe('「此刻生效」不由前端重算', () => {
  it('只显示后端给的版本号，不拿版本号大小去猜', () => {
    expect(effectiveLabel({ effectiveVersionNo: 'v3', versionCount: 5 })).toBe('此刻生效：v3');
  });

  it('没有生效版本是真实状态，要说清是「没生效」而不是「没有版本」', () => {
    expect(effectiveLabel({ effectiveVersionNo: null, versionCount: 5 })).toContain(
      '此刻没有生效版本',
    );
    expect(effectiveLabel({ effectiveVersionNo: null, versionCount: 0 })).toBe('还没有任何版本');
  });
});

describe('倒排行', () => {
  it('逐行说明现在能不能改', () => {
    expect(usageEditableLabel({ versionNo: 'v1', editable: true })).toBe('v1 草稿：可改');
    expect(usageEditableLabel({ versionNo: 'v1', editable: false })).toContain('已冻结');
  });
});

describe('字段校验', () => {
  it('编码与版本号与名称', () => {
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('B'.repeat(65))).toContain('最多 64 个字符');
    expect(versionNoProblem('')).toContain('不能为空');
    expect(versionNoProblem('v'.repeat(33))).toContain('最多 32 个字符');
    expect(versionNameProblem('')).toContain('不能为空');
    expect(versionNameProblem('n'.repeat(201))).toContain('最多 200 个字符');
  });
});

describe('时间往返不丢时区', () => {
  it('datetime-local 存的是本地时间，转成带时区的 ISO 串', () => {
    const iso = localInputToInstant('2026-03-01T00:00');
    // 不能按 UTC 直接拼：那会让东八区选了 18:00 的运营得到 18:00Z = 次日 02:00。
    expect(iso).toMatch(/Z$/);
    expect(new Date(iso).getTime()).toBe(new Date('2026-03-01T00:00').getTime());
  });

  it('空值与非法值返回空串而不是 Invalid Date', () => {
    expect(localInputToInstant('')).toBe('');
    expect(localInputToInstant('not-a-date')).toBe('');
    expect(instantToLocalInput(null)).toBe('');
    expect(instantToLocalInput('not-a-date')).toBe('');
  });
});

describe('错误文案直接透传后端原因', () => {
  it('不包一层「操作失败」', () => {
    expect(errorText(new ApiError(409, '生效区间与已发布版本 v2 冲突'))).toContain('冲突');
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('奇怪的东西')).toBe('请求失败，请稍后重试');
  });
});
