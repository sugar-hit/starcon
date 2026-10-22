import { describe, expect, it } from 'vitest';
import {
  actionTypeLabel,
  appealStatusLabel,
  appealTypeLabel,
  caseStatusLabel,
  causeLabel,
  negativeReviewCaseQuery,
  negativeReviewCandidateQuery,
  negativeReviewRuleQuery,
  negativeReviewStatsQuery,
  outcomeLabel,
  resolutionRateText,
  resolutionStatusLabel,
  ruleScopeLabel,
  transportLabel,
} from './negative-review-model';

/**
 * 15.2.1.3 差评处理前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>筛选条件不能丢</b>：清单与统计走的是**同一套**筛选条件 ——
 *       「页面上看到的」与「解决率算的」必须是同一批处理单，
 *       否则解决率会与清单互相矛盾。</li>
 *   <li><b>解决率不能由前端自己算</b>：比例是**后端**按 numeric 算好的，
 *       前端只做展示格式化；分母为 0 时后端给 null ⇒ 页面显示「—」，
 *       绝不能显示成 0%（那会被读成「一条都没解决」）。</li>
 *   <li><b>「不可申诉」与「可申诉」是两种说法</b>：把不可申诉显示成可申诉，
 *       运营会去提交一条注定被驳回的申诉，白白消耗申诉配额。</li>
 *   <li><b>「已提交」与「待提交」不能混</b>：申诉任务没提交到平台就是「待提交」，
 *       显示成「已提交」会让运营以为事情办完了。</li>
 * </ol>
 */
describe('15.2.1.3 差评处理前端模型', () => {
  describe('筛选条件', () => {
    it('空筛选不产生问号', () => {
      expect(negativeReviewCaseQuery()).toBe('');
      expect(negativeReviewCaseQuery({})).toBe('');
      expect(negativeReviewCandidateQuery()).toBe('');
      expect(negativeReviewRuleQuery()).toBe('');
    });

    it('清单筛选逐项落在查询串里', () => {
      const query = negativeReviewCaseQuery({
        shopId: 'shop-1',
        platformCode: 'AMAZON',
        causeCode: 'LOGISTICS',
        caseStatus: 'OPEN',
        resolutionStatus: 'UNRESOLVED',
        appealEligible: false,
        rating: 1,
        keyword: '差评',
        page: 2,
        size: 20,
      });
      expect(query).toContain('shopId=shop-1');
      expect(query).toContain('platformCode=AMAZON');
      expect(query).toContain('causeCode=LOGISTICS');
      expect(query).toContain('caseStatus=OPEN');
      expect(query).toContain('resolutionStatus=UNRESOLVED');
      // 布尔 false 也要传出去 —— 「只看不可申诉」是一个真实筛选面。
      expect(query).toContain('appealEligible=false');
      expect(query).toContain('rating=1');
      expect(query).toContain('page=2');
      expect(query).toContain('size=20');
    });

    it('统计查询串带 days，且空白筛选时不产出「?&」畸形串', () => {
      expect(negativeReviewStatsQuery({}, 30)).toBe('?days=30');
      expect(negativeReviewStatsQuery({ causeCode: 'LOGISTICS' }, 7)).toBe(
        '?causeCode=LOGISTICS&days=7',
      );
      // 分页参数不该进统计查询（统计与分页无关）。
      expect(negativeReviewStatsQuery({ page: 2, size: 20 }, 30)).toBe('?days=30');
    });

    it('候选差评只列差评时可以显式关掉', () => {
      expect(negativeReviewCandidateQuery({ negativeOnly: true })).toBe('?negativeOnly=true');
      expect(negativeReviewCandidateQuery({ negativeOnly: false })).toBe('?negativeOnly=false');
    });
  });

  describe('解决率只做展示格式化', () => {
    it('后端给的比例原样渲染成分数文本', () => {
      expect(resolutionRateText(0.5)).toBe('50.00%');
      expect(resolutionRateText(1)).toBe('100.00%');
      expect(resolutionRateText(0)).toBe('0.00%');
    });

    it('分母为 0（后端给 null）时显示「—」，不显示 0%', () => {
      // 0% 会被读成「一条都没解决」，而真相是「作用域里一条都没有」。
      expect(resolutionRateText(null)).toBe('—');
      expect(resolutionRateText(undefined)).toBe('—');
      expect(resolutionRateText(null)).not.toBe(resolutionRateText(0));
    });
  });

  describe('可读名', () => {
    it('四个原因分类各有各的说法', () => {
      expect(causeLabel('PRODUCT')).toBe('产品问题');
      expect(causeLabel('LOGISTICS')).toBe('物流问题');
      expect(causeLabel('SERVICE')).toBe('服务问题');
      expect(causeLabel('MISJUDGMENT')).toBe('误判');
      // 未分类与未知取值都要有一个能读的说法，不能渲染成空白。
      expect(causeLabel('')).toBe('（未分类）');
      expect(causeLabel('WHATEVER')).toBe('WHATEVER');
    });

    it('处理状态与解决状态是两组状态，不能混用', () => {
      expect(caseStatusLabel('OPEN')).toBe('待处理');
      expect(caseStatusLabel('IN_PROGRESS')).toBe('处理中');
      expect(caseStatusLabel('RESOLVED')).toBe('已解决');
      expect(caseStatusLabel('CLOSED')).toBe('已关闭');
      expect(resolutionStatusLabel('UNRESOLVED')).toBe('未解决');
      expect(resolutionStatusLabel('RESOLVED')).toBe('已解决');
      expect(resolutionStatusLabel('CLOSED_UNRESOLVED')).toBe('已关闭未解决');
      // 「已关闭」不等于「已解决」—— 混淆会让解决率虚高。
      expect(resolutionStatusLabel('CLOSED_UNRESOLVED')).not.toBe(
        resolutionStatusLabel('RESOLVED'),
      );
    });

    it('动作类型、触达方式与结果各有各的说法', () => {
      expect(actionTypeLabel('CONTACT_BUYER')).toBe('联系买家');
      expect(actionTypeLabel('REQUEST_MODIFICATION')).toBe('请求修改');
      expect(actionTypeLabel('APPEAL_SUBMISSION')).toBe('申诉提交');
      expect(actionTypeLabel('AFTER_SALES_HANDOFF')).toBe('转交售后');
      expect(transportLabel('PLATFORM_API')).toBe('平台接口');
      expect(transportLabel('MANUAL_HANDOFF')).toBe('人工交接');
      expect(outcomeLabel('SUCCEEDED')).toBe('成功');
      expect(outcomeLabel('FAILED')).toBe('失败');
      expect(outcomeLabel('PENDING')).toBe('进行中');
    });

    it('申诉类型与申诉状态各有各的说法', () => {
      expect(appealTypeLabel('REMOVAL')).toBe('申请移除差评');
      expect(appealTypeLabel('MODIFICATION')).toBe('申请修改差评内容');
      expect(appealTypeLabel('POLICY_VIOLATION')).toBe('平台违规申诉');
      expect(appealStatusLabel('DRAFT')).toBe('待提交');
      expect(appealStatusLabel('SUBMITTED')).toBe('已提交');
      expect(appealStatusLabel('APPROVED')).toBe('已通过');
      expect(appealStatusLabel('REJECTED')).toBe('已驳回');
      expect(appealStatusLabel('CANCELLED')).toBe('已撤回');
      // 「待提交」不是「已提交」—— 未提交到平台就是没提交。
      expect(appealStatusLabel('DRAFT')).not.toBe(appealStatusLabel('SUBMITTED'));
    });

    it('规则作用域的说法要能一眼分清', () => {
      expect(ruleScopeLabel('PLATFORM')).toBe('平台策略');
      expect(ruleScopeLabel('CAUSE')).toBe('原因规则');
      expect(ruleScopeLabel('PLATFORM')).not.toBe(ruleScopeLabel('CAUSE'));
    });
  });
});
