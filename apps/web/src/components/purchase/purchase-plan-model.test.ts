import { describe, expect, it } from 'vitest';
import {
  campaignStatusLabel,
  fmt,
  isAdjustable,
  isLockable,
  lineStatusLabel,
  lockStatusLabel,
  lockTone,
  NO_DATA_TEXT,
  periodTypeLabel,
  planStatusLabel,
  planTypeLabel,
  planTypeTone,
  priorityLabel,
  purchasePlanLineQuery,
  purchasePlanQuery,
  suggestedQuantityText,
} from './purchase-plan-model';

/**
 * 6.2.1.3 采购计划前端模型的展示口径断言。
 *
 * <p>这些口径是「蓝图加粗句」在界面上的落点，断言它们是为了让口径改坏时**先红在这里**，
 * 而不是等到页面上没人看出来。
 */

describe('采购计划展示口径', () => {
  it('大促与日常是两个不同的标签，且一眼可分', () => {
    expect(planTypeLabel('ROUTINE')).toBe('日常补货');
    expect(planTypeLabel('CAMPAIGN')).toBe('大促备货');
    expect(planTypeTone('CAMPAIGN')).not.toBe(planTypeTone('ROUTINE'));
  });

  it('计划状态与行状态、锁定状态各有各的名字，不折叠', () => {
    expect(planStatusLabel('DRAFT')).toBe('草稿');
    expect(planStatusLabel('LOCK_FAILED')).toBe('锁定失败');
    expect(lineStatusLabel('ADJUSTED')).toBe('已调整');
    expect(lockStatusLabel('NOT_REQUESTED')).toBe('未请求');
    expect(lockStatusLabel('CONFIRMED')).toBe('已确认');
    expect(lockStatusLabel('FAILED')).toBe('锁定失败');
    // 「未请求」与「已确认」是两个状态 —— 折叠成「已锁定」会让人以为货已经占住了。
    expect(lockStatusLabel('NOT_REQUESTED')).not.toBe(lockStatusLabel('CONFIRMED'));
  });

  it('锁定失败是危险色，其余是中性色', () => {
    expect(lockTone('FAILED')).toBe('danger');
    expect(lockTone('NOT_REQUESTED')).toBe('neutral');
    expect(lockTone('CONFIRMED')).toBe('info');
  });

  it('周期与档期状态有中文名', () => {
    expect(periodTypeLabel('MONTHLY')).toBe('月度');
    expect(periodTypeLabel('QUARTERLY')).toBe('季度');
    expect(campaignStatusLabel('ACTIVE')).toBe('进行中');
    expect(priorityLabel('CRITICAL')).toBe('紧急');
  });

  it('取不到的数量显示「无数据」，绝不显示 0', () => {
    expect(fmt(null)).toBe(NO_DATA_TEXT);
    expect(fmt(undefined)).toBe(NO_DATA_TEXT);
    expect(suggestedQuantityText(null)).toBe(NO_DATA_TEXT);
    // 0 是真实取值，显示成「无数据」同样是错的 —— 两者不能混。
    expect(fmt(0)).toBe('0');
  });
});

describe('锁定与调整的可用性', () => {
  it('已提交请求或已确认锁定的行不再提供锁定入口', () => {
    expect(isLockable('NOT_REQUESTED')).toBe(true);
    expect(isLockable('FAILED')).toBe(true);
    expect(isLockable('REQUESTED')).toBe(false);
    expect(isLockable('CONFIRMED')).toBe(false);
  });

  it('已提交请求或已确认锁定的行不许调整', () => {
    expect(isAdjustable('NOT_REQUESTED')).toBe(true);
    expect(isAdjustable('FAILED')).toBe(true);
    expect(isAdjustable('REQUESTED')).toBe(false);
    expect(isAdjustable('CONFIRMED')).toBe(false);
  });
});

describe('查询串拼装', () => {
  it('空筛选不带查询串', () => {
    expect(purchasePlanQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
  });

  it('筛选条件逐条进查询串并被转义', () => {
    const query = purchasePlanQuery({
      planType: 'CAMPAIGN',
      status: 'DRAFT',
      keyword: 'PP 2026',
      periodStart: '2026-11-01',
      targetWarehouseId: 'warehouse-1',
      sort: 'priority',
      page: 2,
      size: 10,
    });
    expect(query).toContain('planType=CAMPAIGN');
    expect(query).toContain('status=DRAFT');
    expect(query).toContain('keyword=PP%202026');
    expect(query).toContain('periodStart=2026-11-01');
    expect(query).toContain('targetWarehouseId=warehouse-1');
    expect(query).toContain('sort=priority');
    expect(query).toContain('page=2');
    expect(query).toContain('size=10');
  });

  it('空关键字不进查询串（空白不算筛选）', () => {
    expect(purchasePlanQuery({ keyword: '   ', page: 0 })).not.toContain('keyword');
    expect(purchasePlanLineQuery({ keyword: '  ', page: 0 })).not.toContain('keyword');
  });

  it('计划行筛选也逐条进查询串', () => {
    const query = purchasePlanLineQuery({
      skuCode: 'SKU-001',
      status: 'PLANNED',
      lockStatus: 'NOT_REQUESTED',
      page: 1,
      size: 20,
    });
    expect(query).toContain('skuCode=SKU-001');
    expect(query).toContain('status=PLANNED');
    expect(query).toContain('lockStatus=NOT_REQUESTED');
    expect(query).toContain('page=1');
  });
});
