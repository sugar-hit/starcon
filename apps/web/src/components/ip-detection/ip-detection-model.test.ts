import { describe, expect, it } from 'vitest';
import {
  BASE,
  CONCLUSIONS,
  FIELDS,
  RISK_LEVELS,
  alertStatusLabel,
  conclusionLabel,
  conclusionTone,
  emptyState,
  emptyStateKind,
  featureKindLabel,
  fieldLabel,
  hitSourceLabel,
  recommendedActionLabel,
  remediationActionLabel,
  remediationStatusLabel,
  reviewEmptyState,
  reviewStatusLabel,
  riskLevelLabel,
  riskLevelTone,
  runStatusLabel,
  type IpDetectionHit,
} from './ip-detection-model';

/**
 * 16.4.1.2 检测与预警 前端模型的单测。
 *
 * <p>🔴 这组用例守的是**界面上最容易说错话的五件事**：
 *
 * <ol>
 *   <li><b>风险等级沿用上游口径</b>（高 / 中 / 低 + 无命中），不是 0..100 的裸数字；</li>
 *   <li><b>结论三态分开</b>：「待人工复核」<b>不</b>显示成「没问题」（绿色）；</li>
 *   <li><b>图片命中标「待人工复核」</b>，不标「可判定」；</li>
 *   <li><b>命中来源分开说</b>：本地侵权库条目 vs 外部查询结果；</li>
 *   <li><b>空态说清楚为什么空</b>，不让人以为是加载失败。</li>
 * </ol>
 */
describe('16.4.1.2 检测与预警 · 前端模型', () => {
  it('路径前缀是独立的 channel/ip-detections（不是并进既有 channel 分支）', () => {
    expect(BASE).toBe('channel/ip-detections');
  });

  it('风险等级值域沿用上游 HIGH / MEDIUM / LOW，另有 NONE', () => {
    expect(RISK_LEVELS).toEqual(['HIGH', 'MEDIUM', 'LOW', 'NONE']);
    expect(riskLevelLabel('HIGH')).toBe('高风险');
    expect(riskLevelLabel('NONE')).toBe('无命中');
  });

  it('结论是三态，不是两态', () => {
    expect(CONCLUSIONS).toEqual(['CLEAR', 'REVIEW_REQUIRED', 'ACTION_REQUIRED']);
    expect(conclusionLabel('CLEAR')).toBe('无命中');
    expect(conclusionLabel('REVIEW_REQUIRED')).toBe('待人工复核');
    expect(conclusionLabel('ACTION_REQUIRED')).toBe('需整改');
  });

  it('🔴 「待人工复核」不是绿色 —— 绿色会让人以为已经判过、没问题', () => {
    expect(conclusionTone('CLEAR')).toBe('success');
    expect(conclusionTone('REVIEW_REQUIRED')).toBe('warning');
    expect(conclusionTone('ACTION_REQUIRED')).toBe('danger');
    expect(conclusionTone('REVIEW_REQUIRED')).not.toBe('success');
  });

  it('🔴 图片命中标「待人工复核」，可判定的才标「可判定」', () => {
    const imageHit: IpDetectionHit = {
      hitId: 'h1',
      reportId: 'r1',
      entryId: 'e1',
      externalHitId: null,
      entryCode: 'E-1',
      rightType: 'TRADEMARK',
      riskLevel: 'HIGH',
      field: 'IMAGE',
      fieldLabel: '图片',
      featureKind: 'TERM',
      featureKindLabel: '侵权词 / 短语',
      matchedFeature: 'nike',
      matchedText: 'nike-air.jpg',
      needsManualReview: true,
      reviewStatus: 'PENDING',
      createdAt: '2026-10-23T00:00:00Z',
    };
    expect(imageHit.needsManualReview).toBe(true);
    expect(imageHit.field).toBe('IMAGE');
    expect(FIELDS).toContain('IMAGE');
    expect(fieldLabel('IMAGE')).toBe('图片');
  });

  it('🔴 命中来源分开说：本地条目 vs 外部查询结果', () => {
    const base: IpDetectionHit = {
      hitId: 'h1',
      reportId: 'r1',
      entryId: 'e1',
      externalHitId: null,
      entryCode: 'E-1',
      rightType: 'TRADEMARK',
      riskLevel: 'HIGH',
      field: 'TITLE',
      fieldLabel: '标题',
      featureKind: 'TERM',
      featureKindLabel: '侵权词 / 短语',
      matchedFeature: 'nike',
      matchedText: 'nike shoes',
      needsManualReview: false,
      reviewStatus: null,
      createdAt: '2026-10-23T00:00:00Z',
    };
    expect(hitSourceLabel(base)).toContain('侵权库条目');
    expect(hitSourceLabel(base)).toContain('E-1');
    expect(hitSourceLabel({ ...base, entryId: null, externalHitId: 'x1' })).toBe('外部查询结果');
  });

  it('建议动作指向正确的下游，不自己判拦截', () => {
    expect(recommendedActionLabel('INTERCEPT')).toBe('建议下游拦截');
    expect(recommendedActionLabel('VERIFY_AUTHORIZATION')).toBe('建议核验品牌授权');
    expect(recommendedActionLabel('NONE')).toBe('无需动作');
  });

  it('复核状态三态：待复核 / 确认侵权 / 判定误报', () => {
    expect(reviewStatusLabel('PENDING')).toBe('待复核');
    expect(reviewStatusLabel('CONFIRMED')).toBe('确认侵权');
    expect(reviewStatusLabel('DISMISSED')).toBe('判定误报');
  });

  it('整改动作按命中字段分派', () => {
    expect(remediationActionLabel('REPLACE_IMAGE')).toBe('替换图片');
    expect(remediationActionLabel('VERIFY_BRAND_AUTH')).toBe('核验品牌授权');
    expect(remediationActionLabel('REWRITE_TITLE')).toBe('改写标题');
    expect(remediationActionLabel('REMOVE_TERM')).toBe('删除侵权词 / 短语');
    expect(remediationStatusLabel('DONE')).toBe('已办完');
  });

  it('预警状态三态，巡检运行只有终态', () => {
    expect(alertStatusLabel('OPEN')).toBe('待处理');
    expect(alertStatusLabel('RESOLVED')).toBe('已处置');
    expect(runStatusLabel('COMPLETED')).toBe('已完成');
    expect(runStatusLabel('PARTIAL')).toBe('部分完成');
    expect(runStatusLabel('FAILED')).toBe('失败');
  });

  it('风险等级色调：高=红、中=黄、低与无命中=中性（低风险不是绿色）', () => {
    expect(riskLevelTone('HIGH')).toBe('danger');
    expect(riskLevelTone('MEDIUM')).toBe('warning');
    expect(riskLevelTone('LOW')).toBe('neutral');
    expect(riskLevelTone('NONE')).toBe('neutral');
    expect(riskLevelTone('LOW')).not.toBe('success');
  });

  it('空态分两种，筛选无结果与完全没有数据说得不一样', () => {
    expect(emptyStateKind(0, false)).toBe('NO_DATA');
    expect(emptyStateKind(0, true)).toBe('NO_MATCH');
    expect(emptyStateKind(5, true)).toBe('NO_MATCH');
    expect(emptyState('NO_DATA').title).toContain('还没有');
    expect(emptyState('NO_MATCH').title).toContain('筛选条件下');
  });

  it('复核队列为空时说清楚「为什么空」', () => {
    const empty = reviewEmptyState();
    expect(empty.title).toContain('空的');
    expect(empty.description).toContain('图片命中会自动进这里');
  });

  it('特征分类标签覆盖四种来源', () => {
    expect(featureKindLabel('TERM')).toBe('侵权词 / 短语');
    expect(featureKindLabel('BRAND_NAME')).toBe('品牌名');
    expect(featureKindLabel('PATTERN')).toBe('标识符特征');
    expect(featureKindLabel('EXTERNAL_TERM')).toBe('外部查询关键词');
  });
});
