import { describe, expect, it } from 'vitest';
import {
  errorText,
  executionText,
  formatLabels,
  jobStatusLabel,
  jobStatusLabels,
  jobStatusTone,
  limitText,
  objectTypeLabel,
  objectTypeLabels,
  remainingText,
  verdictLabel,
  verdictTone,
  warningText,
  weightText,
} from './label-printing-model';
import type { LabelPrintExecution } from '../../../../../packages/api-contracts/src/index';

/**
 * 8.3.4.2 面单与标签打印前端模型测试。
 *
 * <p>覆盖三处最容易在前端算错的地方：**判定区间的读法**（不限次 ≠ 上限 0）、
 * **状态与判定的配色**（超限要红，不能和正常一样）、
 * 以及**执行结果的文案**（必须说清「第几次、几张、告警几条」）。
 *
 * <p>请求路径与打印次数累加由后端集成测试覆盖（真实 PostgreSQL），这里只测
 * 「同一个数字怎么读」—— 这才是前端唯一会自己算错的东西。
 */
describe('面单与标签打印前端模型', () => {
  describe('五种打印对象', () => {
    it('五种对象都有中文标签', () => {
      expect(Object.values(objectTypeLabels)).toEqual([
        '面单',
        '装箱单',
        'SKU 条码',
        '库位标签',
        '箱唛',
      ]);
    });

    it('未知类型原样透出而不是显示空', () => {
      expect(objectTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(objectTypeLabel(undefined)).toBe('—');
    });
  });

  describe('重复打印判据的读法', () => {
    it('不限次必须说成「不限次」，不能画成上限 0 次', () => {
      // maxCount 为 null = 该平台对此对象类型不限次（但仍然计数、仍然留凭据）
      expect(limitText(null)).toBe('不限次');
      expect(limitText(undefined)).toBe('不限次');
      expect(limitText(3)).toBe('上限 3 次');
      // 上限 0 是配置错误，不该被渲染成「上限 0 次」以外的任何含义
      expect(limitText(0)).toBe('上限 0 次');
    });

    it('没有告警线时说「无告警线」而不是「第 0 次起告警」', () => {
      expect(warningText(null)).toBe('无告警线');
      expect(warningText(2)).toBe('第 2 次起告警');
    });

    it('还剩次数：超限显示「已达上限」，不限次显示「不限」', () => {
      expect(remainingText({ printCount: 1, maxCount: 3 })).toBe('还剩 2 次');
      expect(remainingText({ printCount: 3, maxCount: 3 })).toBe('已达上限');
      expect(remainingText({ printCount: 5, maxCount: 3 })).toBe('已达上限');
      expect(remainingText({ printCount: 9, maxCount: null })).toBe('不限');
    });

    it('三段判定的配色：超限是红的，不是和正常一样', () => {
      expect(verdictTone('NORMAL')).toBe('ok');
      expect(verdictTone('WARNING')).toBe('warn');
      expect(verdictTone('BLOCKED')).toBe('bad');
      expect(verdictLabel('BLOCKED')).toBe('超出平台上限');
      expect(verdictLabel(undefined)).toBe('—');
    });
  });

  describe('任务状态', () => {
    it('六态都有中文标签', () => {
      expect(jobStatusLabels.DRAFT).toBe('草稿');
      expect(jobStatusLabels.QUEUED).toBe('已排队');
      expect(jobStatusLabels.PRINTING).toBe('打印中');
      expect(jobStatusLabels.COMPLETED).toBe('已完成');
      expect(jobStatusLabels.FAILED).toBe('已失败');
      expect(jobStatusLabels.CANCELLED).toBe('已取消');
      expect(jobStatusLabel('COMPLETED')).toBe('已完成');
      expect(jobStatusLabel(undefined)).toBe('—');
    });

    it('只有已完成是绿的，失败是红的', () => {
      expect(jobStatusTone('COMPLETED')).toBe('ok');
      expect(jobStatusTone('FAILED')).toBe('bad');
      expect(jobStatusTone('PRINTING')).toBe('warn');
      expect(jobStatusTone('CANCELLED')).toBe('neutral');
    });
  });

  describe('执行结果文案', () => {
    const base = {
      job: {} as LabelPrintExecution['job'],
      counts: [{ targetId: 'a', externalRef: 'OB-1', printCount: 1 }],
      warnedTargets: 0,
      blockedTargets: 0,
    };

    it('要说出打了几张、几个对象', () => {
      const text = executionText({
        ...base,
        logs: [
          { printSequence: 1 } as LabelPrintExecution['logs'][number],
          { printSequence: 2 } as LabelPrintExecution['logs'][number],
        ],
      });
      expect(text).toBe('已打印 2 张（1 个对象）');
    });

    it('有对象落在告警区间时必须说出来（重复打印是要被平台追责的）', () => {
      const text = executionText({
        ...base,
        warnedTargets: 1,
        logs: [{ printSequence: 3 } as LabelPrintExecution['logs'][number]],
      });
      expect(text).toContain('重复打印告警区间');
    });

    it('没有凭据时说清楚，而不是显示「已打印 0 张」', () => {
      expect(executionText({ ...base, logs: [] })).toBe('本次没有产生打印凭据');
    });
  });

  describe('内容格式与其它', () => {
    it('两种渲染格式都有中文标签', () => {
      expect(formatLabels.ZPL).toBe('ZPL 指令流');
      expect(formatLabels.HTML).toBe('HTML 打印视图');
    });

    it('重量为 0 不是「—」（0 公斤也是有效重量）', () => {
      expect(weightText(0)).toBe('0 kg');
      expect(weightText('1.250')).toBe('1.25 kg');
      expect(weightText(null)).toBe('—');
    });

    it('错误文案原样带出后端讲清的理由，不二次概括', () => {
      expect(errorText(new Error('超出平台重复打印上限：OB-5001'))).toContain('OB-5001');
      expect(errorText('别的异常')).toBe('操作失败，请稍后重试');
    });
  });
});
