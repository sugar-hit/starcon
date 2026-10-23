import { describe, expect, it } from 'vitest';
import {
  actorLabel,
  auditActionLabel,
  auditDecisionLabel,
  auditDecisionTone,
  decisionFor,
  emptyState,
  exportStatusLabel,
  exportStatusTone,
  fieldText,
  fieldValue,
  grantReason,
  isFull,
  revealableFields,
  subjectSummary,
  valueStateLabel,
  valueStateTone,
  type PiiAccessSubjectDetail,
  type PiiAccessSubjectView,
} from './pii-access-model';

/**
 * 16.6.1.1 PII 管理 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **脱敏态与完整态分得清**、**「上游没给」与「空值」是两种文案**、
 * **「凭什么能看到」显示得出来**、**三态空态文案各不相同**。
 */

function detail(overrides: Partial<PiiAccessSubjectDetail> = {}): PiiAccessSubjectDetail {
  return {
    subjectKind: 'ORDER',
    subjectRef: 'order-1',
    subjectLabel: 'A**** Smith',
    known: true,
    fields: [
      {
        fieldCode: 'NAME',
        label: '姓名',
        value: 'A**** Smith',
        masked: true,
        present: true,
      },
      {
        fieldCode: 'ADDRESS',
        label: '地址',
        value: 'US / New York / Manhattan / * *** **',
        masked: true,
        present: true,
      },
      {
        fieldCode: 'PHONE',
        label: '电话',
        value: '',
        masked: true,
        present: false,
      },
    ],
    revealed: false,
    revealNote: '默认脱敏展示：返回的是脱敏值。',
    decisions: [
      {
        fieldCode: 'ADDRESS',
        allowed: true,
        reason: '依据角色「SUPPORT」的授权（范围：全部成员）',
        roleCode: 'SUPPORT',
        grantId: 'grant-1',
      },
      {
        fieldCode: 'NAME',
        allowed: false,
        reason: '没有授权配置允许你的角色查看姓名的完整值',
        roleCode: '',
        grantId: null,
      },
    ],
    ...overrides,
  };
}

function view(overrides: Partial<PiiAccessSubjectView> = {}): PiiAccessSubjectView {
  return {
    subjectKind: 'ORDER',
    subjectRef: 'order-1',
    subjectLabel: 'A**** Smith',
    known: true,
    fields: [
      { fieldCode: 'NAME', fieldLabel: '姓名', maskedValue: 'A**** Smith' },
      { fieldCode: 'ADDRESS', fieldLabel: '地址', maskedValue: 'US / New York / Manhattan' },
    ],
    availableFields: ['NAME', 'ADDRESS'],
    missingFields: ['PHONE'],
    sourceLabel: '9 订单 · 收货地址快照（trade）',
    note: '',
    ...overrides,
  };
}

describe('16.6.1.1 PII 管理前端模型', () => {
  it('字段值取不到时返回 null，不编造', () => {
    expect(fieldValue(detail(), 'NAME')?.value).toBe('A**** Smith');
    expect(fieldValue(detail(), 'EMAIL')).toBeNull();
    expect(fieldValue(null, 'NAME')).toBeNull();
  });

  it('「上游没给这一栏」与「（空）」是两种文案', () => {
    // 上游没给 ⇒ present=false ⇒ 显示「上游没给这一栏」
    expect(fieldText(detail(), 'PHONE')).toBe('上游没给这一栏');
    // 上游给了但是空值 ⇒ 显示「（空）」，**不是**「上游没给」
    expect(
      fieldText(
        detail({
          fields: [{ fieldCode: 'NAME', label: '姓名', value: '', masked: true, present: true }],
        }),
        'NAME',
      ),
    ).toBe('（空）');
    // 取不到的字段显示「—」
    expect(fieldText(detail(), 'EMAIL')).toBe('—');
  });

  it('脱敏态与完整态的徽章分得清', () => {
    expect(valueStateLabel(detail(), 'NAME')).toBe('已脱敏');
    expect(valueStateTone(detail(), 'NAME')).toBe('neutral');
    expect(valueStateLabel(detail(), 'PHONE')).toBe('上游没给');
    expect(valueStateTone(detail(), 'PHONE')).toBe('warning');

    const revealed = detail({
      fields: [
        {
          fieldCode: 'ADDRESS',
          label: '地址',
          value: 'US / New York / Manhattan / 742 Evergreen Terrace',
          masked: false,
          present: true,
        },
      ],
    });
    expect(valueStateLabel(revealed, 'ADDRESS')).toBe('完整值');
    expect(valueStateTone(revealed, 'ADDRESS')).toBe('success');
    expect(isFull(revealed, 'ADDRESS')).toBe(true);
    expect(isFull(detail(), 'ADDRESS')).toBe(false);
  });

  it('只能申请查看仍在脱敏态、且上游给了值的栏', () => {
    expect(revealableFields(detail())).toEqual(['NAME', 'ADDRESS']);
    expect(revealableFields(detail({ fields: [] }))).toEqual([]);
    expect(revealableFields(null)).toEqual([]);
  });

  it('「凭什么能看到」显示角色；没授权时说清原因', () => {
    expect(grantReason(detail(), 'ADDRESS')).toContain('SUPPORT');
    expect(grantReason(detail(), 'NAME')).toContain('没有授权配置');
    expect(grantReason(detail(), 'EMAIL')).toBe('');
    expect(decisionFor(detail(), 'ADDRESS')?.allowed).toBe(true);
    expect(decisionFor(detail(), 'NAME')?.allowed).toBe(false);
    expect(decisionFor(detail(), 'EMAIL')).toBeNull();
  });

  it('主体列表摘要用脱敏值，查无此人如实说', () => {
    expect(subjectSummary(view())).toContain('A**** Smith');
    expect(subjectSummary(view({ known: false, fields: [] }))).toBe('上游查无这个主体');
  });

  it('导出申请状态与审计动作各有各的标签', () => {
    expect(exportStatusLabel('PENDING')).toBe('待审批');
    expect(exportStatusLabel('EXECUTED')).toBe('已导出');
    expect(exportStatusTone('REJECTED')).toBe('danger');
    expect(auditActionLabel('VIEW_FULL')).toBe('查看完整值');
    expect(auditDecisionLabel('DENIED')).toBe('拒绝');
    expect(auditDecisionTone('ALLOWED')).toBe('success');
  });

  it('成员标识用短码显示（拿不到显示名时也不编造）', () => {
    expect(actorLabel('11111111-2222-3333-4444-555555555555')).toBe('成员 11111111');
    expect(actorLabel('')).toBe('未知成员');
  });

  it('五种空态文案各不相同', () => {
    const titles = [
      emptyState('NO_POLICY').title,
      emptyState('NO_SUBJECT').title,
      emptyState('NO_EXPORT').title,
      emptyState('NO_AUDIT').title,
      emptyState('UNKNOWN_SUBJECT').title,
    ];
    expect(new Set(titles).size).toBe(5);
    expect(emptyState('UNKNOWN_SUBJECT').description).toContain('查无此人');
    expect(emptyState('NO_EXPORT').description).toContain('审批');
  });
});
