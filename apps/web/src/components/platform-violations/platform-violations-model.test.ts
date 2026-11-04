import { describe, expect, it } from 'vitest';
import {
  BASE,
  EVIDENCE_TYPES,
  MEASURES,
  REMEDIATION_STATUSES,
  SOURCES,
  STATUSES,
  buildDeductionInput,
  buildEvidenceInput,
  buildRemediationInput,
  buildSubjectInput,
  emptyState,
  emptyStateKind,
  impactLabel,
  impactTone,
  isUuid,
  measureLabel,
  moneyText,
  remediationStatusLabel,
  sellableText,
  sourceLabel,
  statusLabel,
  statusTone,
  totalsText,
  violationTypeLabel,
} from './platform-violations-model';

/**
 * 16.1.2.1 违规与处罚 前端模型的单测。
 *
 * 🔴 这组用例守的是六件最容易做丢的事：
 *
 * 1. **扣款金额永远带币种**，没有金额时显示成空串而不是 0。
 * 2. **不同币种不直接相加**：合计按币种分组，**没有**跨币种的总数。
 * 3. **「尚无处罚措施」不等于「没问题」** —— 色调与文案都单独一份。
 * 4. **「读不到库存」不等于「没有库存」** —— 显示「未知」，不显示 0。
 * 5. **值域与后端一致**：处罚措施恰好五种（警告 / 下架 / 限制销售 / 扣款 / 停号）。
 * 6. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 */
describe('处罚措施值域（受控值域，与后端 CHECK 一致）', () => {
  it('恰好是蓝图点名的五种', () => {
    expect(MEASURES).toEqual([
      'WARNING',
      'DELIST',
      'RESTRICT_SALES',
      'DEDUCTION',
      'SUSPEND_ACCOUNT',
    ]);
    expect(measureLabel('WARNING')).toBe('警告');
    expect(measureLabel('DELIST')).toBe('下架');
    expect(measureLabel('RESTRICT_SALES')).toBe('限制销售');
    expect(measureLabel('DEDUCTION')).toBe('扣款');
    expect(measureLabel('SUSPEND_ACCOUNT')).toBe('停号');
  });

  it('处理状态与整改状态的值域与后端一致', () => {
    expect(STATUSES).toEqual(['PENDING', 'IN_PROGRESS', 'RECTIFIED', 'APPEALING', 'CLOSED']);
    expect(REMEDIATION_STATUSES).toEqual([
      'OPEN',
      'IN_PROGRESS',
      'SUBMITTED',
      'VERIFIED',
      'REJECTED',
    ]);
    expect(SOURCES).toEqual(['PLATFORM_SYNC', 'MANUAL']);
    expect(statusLabel('PENDING')).toBe('未处理');
    expect(statusLabel('IN_PROGRESS')).toBe('处理中');
    expect(statusLabel('RECTIFIED')).toBe('已整改');
    expect(statusLabel('APPEALING')).toBe('申诉中');
    expect(statusLabel('CLOSED')).toBe('已关闭');
    expect(remediationStatusLabel('SUBMITTED')).toContain('待验收');
  });

  it('违规类型的人话名', () => {
    expect(violationTypeLabel('PRODUCT_SAFETY')).toContain('产品安全');
    expect(violationTypeLabel('PROHIBITED_ITEM')).toBe('违禁品');
    expect(violationTypeLabel('NOT_A_TYPE')).toBe('NOT_A_TYPE');
  });

  it('来源的人话名区分平台同步与人工登记', () => {
    expect(sourceLabel('PLATFORM_SYNC')).toBe('平台同步');
    expect(sourceLabel('MANUAL')).toBe('人工登记');
  });
});

describe('🔴 扣款金额带币种，且不跨币种合计', () => {
  it('金额展示串带币种', () => {
    expect(moneyText(120.5, 'USD')).toBe('120.50 USD');
    expect(moneyText(0, 'EUR')).toBe('0.00 EUR');
  });

  it('没有金额时显示成空串，绝不是「0」', () => {
    expect(moneyText(null, null)).toBe('');
    expect(moneyText(undefined, undefined)).toBe('');
    expect(moneyText(null, null)).not.toBe('0');
    expect(moneyText(null, null)).not.toBe('0.00');
  });

  it('🔴 多个币种时明确写「未折算、未合计」，绝不加成一个数', () => {
    const text = totalsText([
      { currency: 'EUR', amount: 50, penaltyCount: 1 },
      { currency: 'USD', amount: 125, penaltyCount: 2 },
    ]);
    expect(text).toContain('EUR 50.00');
    expect(text).toContain('USD 125.00');
    expect(text).toContain('未折算');
    expect(text).toContain('未合计');
    // 🔴 不能出现一个「总额」把两个币种混在一起。
    expect(text).not.toContain('175');
  });

  it('只有一个币种时直接显示那个币种的合计', () => {
    expect(totalsText([{ currency: 'USD', amount: 125, penaltyCount: 2 }])).toBe('USD 125.00');
  });

  it('一条扣款都没有时显示「暂无扣款」', () => {
    expect(totalsText([])).toBe('暂无扣款');
    expect(totalsText(null)).toBe('暂无扣款');
  });
});

describe('🔴 影响档位：派生，不是存的', () => {
  it('档位的人话名', () => {
    expect(impactLabel('CRITICAL')).toContain('停号');
    expect(impactLabel('HIGH')).toContain('下架');
    expect(impactLabel('MEDIUM')).toContain('扣款');
    expect(impactLabel('LOW')).toContain('警告');
  });

  it('🔴「尚无处罚措施」有自己的档位与文案，不等于「没问题」', () => {
    expect(impactLabel('NONE')).toContain('尚无处罚措施');
    expect(impactLabel('NONE')).not.toContain('正常');
    expect(impactLabel('NONE')).not.toContain('健康');
  });

  it('🔴「尚无处罚措施」用中性色，不与「轻微」共用绿色', () => {
    expect(impactTone('NONE')).toBe('neutral');
    expect(impactTone('LOW')).toBe('success');
    expect(impactTone('CRITICAL')).toBe('danger');
    expect(impactTone('HIGH')).toBe('danger');
    expect(impactTone('MEDIUM')).toBe('warning');
  });
});

describe('🔴 处理状态色调', () => {
  it('未处理最严重、已关闭中性', () => {
    expect(statusTone('PENDING')).toBe('danger');
    expect(statusTone('IN_PROGRESS')).toBe('warning');
    expect(statusTone('RECTIFIED')).toBe('success');
    expect(statusTone('APPEALING')).toBe('warning');
    expect(statusTone('CLOSED')).toBe('neutral');
  });
});

describe('🔴 库存「读不到」不等于「没有库存」', () => {
  it('读不到 / 没有库存行显示成「未知」，绝不显示 0', () => {
    expect(
      sellableText({
        skuId: 'sku-1',
        skuCode: 'SKU-1',
        sellable: null,
        found: false,
        note: '',
      }),
    ).toBe('未知');
    expect(
      sellableText({
        skuId: 'sku-1',
        skuCode: 'SKU-1',
        sellable: 0,
        found: true,
        note: '',
      }),
    ).toBe('0.00');
  });
});

describe('🔴 三态空态各不相同', () => {
  it('没有店铺 / 还没登记过违规 / 筛选无结果 分开说', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
    expect(emptyStateKind(3, 0, false)).toBe('NO_DATA');
    expect(emptyStateKind(3, 0, true)).toBe('NO_MATCH');
    expect(emptyStateKind(3, 5, false)).toBe('NO_MATCH');
  });

  it('三种空态的标题与说明都不相同', () => {
    const titles = new Set([
      emptyState('NO_SHOP').title,
      emptyState('NO_DATA').title,
      emptyState('NO_MATCH').title,
    ]);
    expect(titles.size).toBe(3);
    const descriptions = new Set([
      emptyState('NO_SHOP').description,
      emptyState('NO_DATA').description,
      emptyState('NO_MATCH').description,
    ]);
    expect(descriptions.size).toBe(3);
  });

  it('「还没登记过违规」这句话说清了从哪来', () => {
    expect(emptyState('NO_DATA').description).toContain('平台违规通知同步');
    expect(emptyState('NO_DATA').description).toContain('人工登记');
  });
});

describe('白名单前缀独立', () => {
  it('BASE 是本模块自己的独立前缀（不并进既有 channel 分支）', () => {
    expect(BASE).toBe('channel/platform-violations');
  });
});

/**
 * 🔴 写进真实违规记录的入参，一律不许带占位值。
 *
 * <p>这一组用例守的是本模块最贵的一个教训：占位值不一定是「假数据」，
 * 它更常见的两副面孔是
 *
 * <ul>
 *   <li><b>假装成功</b> —— 后端 422 掉了，前端把错误吞掉，界面看着像挂上了；
 *   <li><b>真的落库</b> —— 后端只校验「非空」，一句通用文案就写进了真实记录，
 *       事后追责时全是假线索（写死的扣款金额还要跟着店铺走进 12 财务）。
 * </ul>
 */
describe('🔴 主体挂载：不许塞占位 id', () => {
  it('SKU 必须给真实 UUID，且 skuId / listingId / orderRef 只填对应那一个', () => {
    const built = buildSubjectInput({
      subjectType: 'sku',
      subjectRef: '11111111-1111-4111-8111-111111111111',
    });
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    expect(built.value.subjectType).toBe('SKU');
    expect(built.value.skuId).toBe('11111111-1111-4111-8111-111111111111');
    expect(built.value.listingId).toBeNull();
    expect(built.value.orderRef).toBeNull();
  });

  it('LISTING 填 listingId，ORDER 填订单号', () => {
    const listing = buildSubjectInput({
      subjectType: 'LISTING',
      subjectRef: '22222222-2222-4222-8222-222222222222',
    });
    expect(listing.ok).toBe(true);
    if (listing.ok) {
      expect(listing.value.listingId).toBe('22222222-2222-4222-8222-222222222222');
      expect(listing.value.skuId).toBeNull();
    }

    const order = buildSubjectInput({ subjectType: 'ORDER', subjectRef: 'AMZ-2026-0001' });
    expect(order.ok).toBe(true);
    if (order.ok) {
      expect(order.value.orderRef).toBe('AMZ-2026-0001');
      expect(order.value.skuId).toBeNull();
    }
  });

  it('🔴 SKU / LISTING 的 id 不是 UUID 就直接拒绝，不产出可提交的入参', () => {
    const built = buildSubjectInput({ subjectType: 'SKU', subjectRef: 'MANUAL-SKU' });
    expect(built.ok).toBe(false);
    if (built.ok) return;
    expect(built.error).toContain('UUID');
  });

  it('🔴 引用为空、类型不认识，都拒绝', () => {
    expect(buildSubjectInput({ subjectType: 'SKU', subjectRef: '  ' }).ok).toBe(false);
    expect(buildSubjectInput({ subjectType: 'WAREHOUSE', subjectRef: 'x' }).ok).toBe(false);
  });

  it('🔴 产出值里没有任何写死的示例引用', () => {
    const built = buildSubjectInput({
      subjectType: 'ORDER',
      subjectRef: 'AMZ-2026-0001',
    });
    if (!built.ok) return;
    expect(built.value.subjectRef).toBe('AMZ-2026-0001');
    expect(built.value.note).toBe('');
  });

  it('UUID 形状校验认得真 UUID、认不得占位串', () => {
    expect(isUuid('11111111-1111-4111-8111-111111111111')).toBe(true);
    expect(isUuid('11111111-1111-4111-8111-11111111111')).toBe(false);
    expect(isUuid('MANUAL-SKU')).toBe(false);
  });
});

describe('🔴 整改要求：必须是用户真写的内容', () => {
  it('空要求被拒', () => {
    const built = buildRemediationInput({ requirement: '   ', ownerRef: '', dueAt: '' });
    expect(built.ok).toBe(false);
    if (!built.ok) expect(built.error).toContain('不能为空');
  });

  it('期限格式不对被拒（YYYY-MM-DD）', () => {
    const built = buildRemediationInput({
      requirement: '补齐材质报告',
      ownerRef: '张三',
      dueAt: '2026/12/31',
    });
    expect(built.ok).toBe(false);
    if (!built.ok) expect(built.error).toContain('YYYY-MM-DD');
  });

  it('合法输入被接受，前后空白被裁掉，期限留空变成 null', () => {
    const built = buildRemediationInput({
      requirement: '  补齐材质报告  ',
      ownerRef: ' 李四 ',
      dueAt: '',
    });
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    expect(built.value).toEqual({
      requirement: '补齐材质报告',
      ownerRef: '李四',
      dueAt: null,
    });
  });
});

describe('🔴 整改凭证：引用或说明至少给一个，且不许是假文件名', () => {
  it('类型不在后端白名单里就拒绝', () => {
    const built = buildEvidenceInput({
      evidenceType: 'PDF',
      evidenceRef: 'x.pdf',
      summary: 'y',
    });
    expect(built.ok).toBe(false);
    if (!built.ok) expect(built.error).toContain('DOCUMENT');
    expect(EVIDENCE_TYPES).toEqual(['DOCUMENT', 'IMAGE', 'VIDEO', 'LINK', 'NOTE']);
  });

  it('引用与说明都空被拒', () => {
    const built = buildEvidenceInput({
      evidenceType: 'DOCUMENT',
      evidenceRef: ' ',
      summary: ' ',
    });
    expect(built.ok).toBe(false);
    if (!built.ok) expect(built.error).toContain('至少');
  });

  it('只给引用也算合法（后端允许二选一）', () => {
    const built = buildEvidenceInput({
      evidenceType: 'DOCUMENT',
      evidenceRef: 'material-report.pdf',
      summary: '',
    });
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    expect(built.value.evidenceRef).toBe('material-report.pdf');
    expect(built.value.summary).toBe('');
  });
});

describe('🔴 扣款：金额与币种都得用户给，且金额必须大于 0', () => {
  it('金额不是数字 / 是 0 / 是负数，都拒绝', () => {
    expect(buildDeductionInput({ amount: 'abc', currency: 'USD' }).ok).toBe(false);
    expect(buildDeductionInput({ amount: '0', currency: 'USD' }).ok).toBe(false);
    expect(buildDeductionInput({ amount: '-5', currency: 'USD' }).ok).toBe(false);
    expect(buildDeductionInput({ amount: '', currency: 'USD' }).ok).toBe(false);
  });

  it('币种必须是三位大写字母', () => {
    expect(buildDeductionInput({ amount: '10', currency: 'US' }).ok).toBe(false);
    expect(buildDeductionInput({ amount: '10', currency: '人民币' }).ok).toBe(false);
    expect(buildDeductionInput({ amount: '10', currency: '' }).ok).toBe(false);
  });

  it('合法输入被接受，币种被归一成大写', () => {
    const built = buildDeductionInput({ amount: ' 120.50 ', currency: 'usd' });
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    expect(built.value).toEqual({
      measure: 'DEDUCTION',
      penaltyAmount: 120.5,
      penaltyCurrency: 'USD',
    });
  });
});
