import { describe, expect, it } from 'vitest';

import {
  EMPTY_QUALIFICATION_FILTERS,
  EMPTY_REMINDER_FILTERS,
  blockingTone,
  dateText,
  emptyDocumentForm,
  emptyQualificationForm,
  expiryText,
  expiryTone,
  fileSizeText,
  instantText,
  reminderStatusTone,
  reminderTone,
  reviewTone,
  scopeLabel,
  validateDocument,
  validateQualification,
  validateReview,
  validityProblem,
  validityText,
} from './qualifications-model';
import type { QualificationInput } from './qualifications-model';

/**
 * 6.1.2.3 供应商资质前端模型的**口径**断言。
 *
 * <p>这些断言盯的是蓝图加粗句「**资质过期必须阻断相关商品的刊登与发货，不能只做提醒**」
 * 在界面上有没有守住：
 *
 * <ul>
 *   <li>「即将过期」与「已过期」是**两个不同的状态**：措辞不同、颜色不同 ——
 *       前者还在有效期内（该去续期），后者已经越界（该停下来）；
 *   <li>审核状态与到期状态是**两列**，不揉成一个标签；
 *   <li>提醒 ≠ 阻断：两者各有各的展示位；
 *   <li>授权书必须关联 03 品牌 → 本地拦一次（后端与 DB CHECK 各再拦一道）；
 *   <li>有效期终点必须晚于起点 → 本地拦一次。
 * </ul>
 */

describe('即将过期与已过期是两个状态', () => {
  it('两种状态用两种颜色，不归成同一个 warning', () => {
    expect(expiryTone('EXPIRING')).toBe('warning');
    expect(expiryTone('EXPIRED')).toBe('danger');
    expect(expiryTone('VALID')).toBe('success');
    expect(expiryTone('NOT_EFFECTIVE')).toBe('neutral');
  });

  it('即将过期写「还剩几天」，已过期写「过了几天」', () => {
    expect(expiryText('EXPIRING', 10)).toBe('10 天后过期');
    expect(expiryText('EXPIRED', -12)).toBe('已过期 12 天');
    expect(expiryText('EXPIRED', 0)).toBe('已过期');
  });

  it('有效 / 未生效各有各的说法，不混成「有效」', () => {
    expect(expiryText('VALID', 100)).toBe('有效');
    expect(expiryText('NOT_EFFECTIVE', 100)).toBe('尚未生效');
    expect(expiryText(null, null)).toBe('—');
  });
});

describe('审核状态与提醒状态', () => {
  it('审核状态分得清：通过 / 待审 / 驳回与撤销', () => {
    expect(reviewTone('APPROVED')).toBe('success');
    expect(reviewTone('PENDING')).toBe('warning');
    expect(reviewTone('REJECTED')).toBe('danger');
    expect(reviewTone('REVOKED')).toBe('danger');
    expect(reviewTone('DRAFT')).toBe('neutral');
  });

  it('提醒类别分得清：即将过期 / 已过期', () => {
    expect(reminderTone('EXPIRING')).toBe('warning');
    expect(reminderTone('EXPIRED')).toBe('danger');
  });

  it('提醒处理状态分得清：待处理 / 已确认 / 已处理', () => {
    expect(reminderStatusTone('OPEN')).toBe('danger');
    expect(reminderStatusTone('ACKNOWLEDGED')).toBe('warning');
    expect(reminderStatusTone('RESOLVED')).toBe('success');
  });
});

describe('阻断判定', () => {
  it('阻断与不阻断用两种色', () => {
    expect(blockingTone(true)).toBe('danger');
    expect(blockingTone(false)).toBe('success');
  });

  it('判定范围各有各的名字', () => {
    expect(scopeLabel('SUPPLIER')).toBe('供应商维度');
    expect(scopeLabel('BRAND')).toBe('品牌维度');
    expect(scopeLabel('SKU')).toBe('商品维度');
    expect(scopeLabel(null)).toBe('—');
  });
});

describe('时间与文本', () => {
  it('有效期窗口两端都显示，缺一端显示「—」', () => {
    expect(validityText('2026-05-01T00:00Z', '2027-05-01T00:00Z')).toBe('2026-05-01 ~ 2027-05-01');
    expect(validityText(null, null)).toBe('—');
  });

  it('时刻只到分钟，日期只到日', () => {
    expect(instantText('2026-05-01T08:30:00Z')).toBe('2026-05-01 08:30');
    expect(instantText(null)).toBe('—');
    expect(dateText('2026-05-01T08:30:00Z')).toBe('2026-05-01');
  });

  it('文件大小按量级显示', () => {
    expect(fileSizeText(512)).toBe('512 B');
    expect(fileSizeText(2048)).toBe('2.0 KB');
    expect(fileSizeText(null)).toBe('—');
  });
});

describe('本地校验', () => {
  const base: QualificationInput = {
    ...emptyQualificationForm(),
    code: 'Q-001',
    supplierRoleId: 'supplier-1',
    qualificationType: 'BUSINESS_LICENSE',
    title: '营业执照',
    validFrom: '2026-01-01',
    validUntil: '2027-01-01',
  };

  it('合法的登记表单没有拦截', () => {
    expect(validateQualification(base)).toEqual([]);
  });

  it('授权书必须关联 03 品牌（品牌授权需关联 03 品牌）', () => {
    const problems = validateQualification({
      ...base,
      qualificationType: 'AUTHORIZATION_LETTER',
      brandId: null,
    });
    expect(problems.join('；')).toContain('必须关联 03 品牌');
  });

  it('授权书关联了品牌就放行', () => {
    expect(
      validateQualification({
        ...base,
        qualificationType: 'AUTHORIZATION_LETTER',
        brandId: 'brand-1',
      }),
    ).toEqual([]);
  });

  it('编码 / 供应商 / 证件名称 / 有效期都必填', () => {
    const problems = validateQualification({
      ...base,
      code: '',
      supplierRoleId: '',
      title: '',
      validFrom: '',
      validUntil: '',
    });
    expect(problems.join('；')).toContain('资质编码必填');
    expect(problems.join('；')).toContain('供应商必填');
    expect(problems.join('；')).toContain('证件名称必填');
    expect(problems.join('；')).toContain('有效期起点必填');
    expect(problems.join('；')).toContain('有效期终点必填');
  });

  it('有效期终点必须晚于起点', () => {
    const problems = validateQualification({
      ...base,
      validFrom: '2027-01-01',
      validUntil: '2026-01-01',
    });
    expect(problems.join('；')).toContain('有效期终点必须晚于起点');
  });

  it('驳回与撤销必须写明理由', () => {
    expect(validateReview('REJECT', '').join('；')).toContain('必须写明理由');
    expect(validateReview('REVOKE', '  ').join('；')).toContain('必须写明理由');
    expect(validateReview('APPROVE', '')).toEqual([]);
    expect(validateReview('SUBMIT', '')).toEqual([]);
  });

  it('文件 key 必填，大小不是数字要拦下来', () => {
    expect(validateDocument(emptyDocumentForm()).join('；')).toContain('文件 key 必填');
    expect(
      validateDocument({
        ...emptyDocumentForm(),
        documentKey: 's3://b/a.pdf',
        sizeBytes: 'abc',
      }).join('；'),
    ).toContain('文件大小请填数字');
    expect(
      validateDocument({ ...emptyDocumentForm(), documentKey: 's3://b/a.pdf', sizeBytes: '1024' }),
    ).toEqual([]);
  });
});

describe('有效期起止判定', () => {
  it('终点晚于起点才是合法的', () => {
    expect(validityProblem('2026-01-01', '2027-01-01')).toBeNull();
    expect(validityProblem('2027-01-01', '2026-01-01')).toBe('有效期终点必须晚于起点');
  });

  it('同一天也算合法：终点按当日最后一微秒算', () => {
    expect(validityProblem('2026-01-01', '2026-01-01')).toBeNull();
  });

  it('不合法的时间被拦下来，不当成「合法」', () => {
    expect(validityProblem('不是时间', '2027-01-01')).toBe('有效期不是合法的时间');
  });
});

describe('初值', () => {
  it('筛选初值带默认排序（按到期日，近的在前）', () => {
    expect(EMPTY_QUALIFICATION_FILTERS.sort).toBe('validUntil');
    expect(EMPTY_QUALIFICATION_FILTERS.desc).toBe(true);
    expect(EMPTY_QUALIFICATION_FILTERS.page).toBe(0);
  });

  it('提醒筛选初值是空', () => {
    expect(EMPTY_REMINDER_FILTERS.reminderKind).toBe('');
    expect(EMPTY_REMINDER_FILTERS.status).toBe('');
  });

  it('登记表单初值默认是营业执照', () => {
    expect(emptyQualificationForm().qualificationType).toBe('BUSINESS_LICENSE');
    expect(emptyDocumentForm().documentKind).toBe('ORIGINAL');
  });
});
