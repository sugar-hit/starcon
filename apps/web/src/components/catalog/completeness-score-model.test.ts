import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import {
  certificationCodeProblem,
  certificationProblem,
  certificationCompletenessLabel,
  certificationTone,
  cleanElements,
  completenessErrorText,
  countByCode,
  customsHasContent,
  customsProblem,
  dimensionProgress,
  gapNextEntryLabel,
  gradeLabel,
  gradeTone,
  hsCodeProblem,
  originCountryProblem,
  ratioText,
  readinessLabel,
  sampleOwnersText,
  scoreText,
} from './completeness-score-model';

/**
 * 3.9.2.1 完整度评分前端模型测试。
 *
 * <p>重点是**反向断言**：把"算不出来"渲染成 0、把"无需认证"当成缺失、
 * 把分数当百分数再乘一次 100 —— 这三个都曾经是最容易悄悄错掉的地方。
 */
describe('completeness-score-model', () => {
  describe('分数与分档（R2：后端算分，前端只翻译）', () => {
    it('优先用后端给的百分数串，不在前端再乘一次 100', () => {
      expect(scoreText(0.7543, '75.43')).toBe('75.43%');
      // 没有 percent 时才自己换算 —— score 是 0..1 的比值
      expect(scoreText(0.7543)).toBe('75.43%');
    });

    it('null 分数显示破折号而不是 0%', () => {
      // 0.00% 会被读成"一分没得"，而"还没算出来"是完全不同的状态
      expect(scoreText(null)).toBe('—');
      expect(scoreText(undefined)).toBe('—');
      expect(ratioText(null)).toBe('—');
    });

    it('真正的 0 分仍然是 0.00% —— 不能与"算不出来"混为一谈', () => {
      expect(scoreText(0)).toBe('0.00%');
      expect(ratioText(0)).toBe('0.0%');
    });

    it('分档阈值由后端决定，前端只映射 code 与颜色', () => {
      expect(gradeLabel('EXCELLENT')).toBe('优秀');
      expect(gradeLabel('POOR')).toBe('较差');
      expect(gradeLabel(null)).toBe('未评分');
      expect(gradeLabel('WHATEVER')).toBe('未评分');
      expect(gradeTone('GOOD')).toBe('ok');
      expect(gradeTone('POOR')).toBe('danger');
      expect(gradeTone('FAIR')).toBe('warn');
    });
  });

  describe('维度进度（R4：算不出来 ≠ 完成了 0%）', () => {
    it('total 为 0 显示不适用而不是 0 / 0', () => {
      expect(dimensionProgress(0, 0)).toBe('不适用');
    });

    it('正常维度显示已完成 / 总数', () => {
      expect(dimensionProgress(3, 4)).toBe('3 / 4');
      expect(dimensionProgress(0, 4)).toBe('0 / 4');
    });
  });

  describe('就绪结论（R3：两条链分开，不合并成一个百分比）', () => {
    it('就绪时只说就绪', () => {
      expect(readinessLabel(true, [])).toBe('就绪');
    });

    it('未就绪时把阻塞原因列出来', () => {
      expect(readinessLabel(false, ['报关属性', '包装尺寸重量'])).toBe(
        '未就绪：报关属性、包装尺寸重量',
      );
      // 没有具体原因时也要说"未就绪"，不能给一个空串
      expect(readinessLabel(false, [])).toBe('未就绪');
    });
  });

  describe('认证四态（R4 / R5：无需认证是完整的两种方式之一）', () => {
    it('四种结论各有各的中文名', () => {
      expect(certificationCompletenessLabel('COMPLETE')).toBe('已齐全');
      expect(certificationCompletenessLabel('PENDING')).toBe('已申请未取得');
      expect(certificationCompletenessLabel('EXPIRED')).toBe('已过期待续期');
      expect(certificationCompletenessLabel('NONE')).toBe('未登记');
      expect(certificationCompletenessLabel(null)).toBe('未登记');
    });

    it('无需认证与已取得都是绿色 —— 否则"确认不需要"看起来像缺失', () => {
      expect(certificationTone('NOT_REQUIRED')).toBe('ok');
      expect(certificationTone('HELD')).toBe('ok');
      expect(certificationTone('PENDING')).toBe('warn');
      expect(certificationTone('EXPIRED')).toBe('danger');
    });

    it('HELD 必须有证书编号，NOT_REQUIRED 不能有编号与有效期', () => {
      expect(certificationProblem({ certificationCode: 'CE', status: 'HELD' })).toContain(
        '证书编号',
      );
      expect(
        certificationProblem({ certificationCode: 'CE', status: 'HELD', certificateNo: 'CE-1' }),
      ).toBeNull();
      expect(
        certificationProblem({
          certificationCode: 'CE',
          status: 'NOT_REQUIRED',
          certificateNo: 'CE-1',
        }),
      ).toContain('无需认证');
      expect(
        certificationProblem({
          certificationCode: 'CE',
          status: 'NOT_REQUIRED',
          expiresOn: '2030-01-01',
        }),
      ).toContain('无需认证');
    });

    it('状态必须显式选一个，缺省不放行', () => {
      expect(certificationProblem({ certificationCode: 'CE' })).toContain('认证状态必填');
      expect(certificationProblem({ certificationCode: 'CE', status: 'MAYBE' })).toContain(
        '不在词表',
      );
    });

    it('认证代码只含大写字母数字', () => {
      expect(certificationCodeProblem('')).toContain('必填');
      expect(certificationCodeProblem('ce')).toBeNull();
      expect(certificationCodeProblem('CE Mark')).toContain('只含大写字母与数字');
      expect(certificationCodeProblem('3C')).toBeNull();
    });

    it('到期日不能早于签发日', () => {
      expect(
        certificationProblem({
          certificationCode: 'UL',
          status: 'HELD',
          certificateNo: 'UL-1',
          issuedOn: '2024-01-01',
          expiresOn: '2023-01-01',
        }),
      ).toContain('不能早于签发日');
    });
  });

  describe('HS 编码与原产国', () => {
    it('小数点与短横线会被去掉，所以同码只有一种写法', () => {
      expect(hsCodeProblem('8471.30-01')).toBeNull();
      expect(hsCodeProblem('  847130 ')).toBeNull();
    });

    it('空值不算问题 —— 缺什么由评分报，不在表单层拦', () => {
      expect(hsCodeProblem('')).toBeNull();
      expect(originCountryProblem('')).toBeNull();
    });

    it('非法字符被拒', () => {
      expect(hsCodeProblem('8471 30%')).toContain('只含数字与字母');
      expect(originCountryProblem('CHN')).toContain('两位国家码');
      expect(originCountryProblem('cn')).toBeNull();
    });
  });

  describe('报关属性', () => {
    it('全空的报关行被拒 —— 它在评分里与"未登记"完全一样', () => {
      expect(customsHasContent({})).toBe(false);
      expect(customsProblem({})).toContain('至少要填一项');
    });

    it('只填一项就算有内容（报关属性是逐步补全的）', () => {
      expect(customsHasContent({ declaredName: '便携式电脑' })).toBe(true);
      expect(customsProblem({ declaredName: '便携式电脑' })).toBeNull();
      // 申报要素里全是空白也不算内容
      expect(customsHasContent({ declarationElements: ['  ', ''] })).toBe(false);
    });

    it('申报品名与法定计量单位有长度上限', () => {
      expect(customsProblem({ declaredName: 'x'.repeat(201) })).toContain('申报品名');
      expect(customsProblem({ legalUnit: 'y'.repeat(33) })).toContain('法定计量单位');
    });

    it('空白申报要素被剔掉', () => {
      expect(cleanElements([' 棉 ', '', '  ', '95%'])).toEqual(['棉', '95%']);
    });
  });

  describe('缺失项聚合与样本', () => {
    it('按缺失码计数', () => {
      expect(countByCode(['A', 'A', 'B'])).toEqual(
        new Map([
          ['A', 2],
          ['B', 1],
        ]),
      );
      expect(countByCode([])).toEqual(new Map());
    });

    it('空样本显示破折号而不是空串', () => {
      // 空串会被界面当成"没数据"，而"一个主体都没有"是要报的事实
      expect(sampleOwnersText([])).toBe('—');
      expect(sampleOwnersText(['A-1', 'A-2'])).toBe('A-1、A-2');
    });

    it('下一步入口按后端给的路由映射', () => {
      expect(gapNextEntryLabel('/products/media')).toBe('去传图');
      expect(gapNextEntryLabel('/products/completeness')).toBe('去补报关 / 认证');
      expect(gapNextEntryLabel(null)).toBe('去处理');
    });
  });

  describe('错误态（401 / 403 / 其它必须分开）', () => {
    it('401 说重新登录，403 说没权限，其它透传后端 message', () => {
      expect(completenessErrorText(new ApiError(401, ''))).toContain('重新登录');
      expect(completenessErrorText(new ApiError(403, ''))).toContain('权限');
      expect(completenessErrorText(new ApiError(409, '报关属性已被他人修改'))).toBe(
        '报关属性已被他人修改',
      );
    });

    it('不认识的错误也给得出可读文案', () => {
      expect(completenessErrorText(new Error('boom'))).toBe('boom');
      expect(completenessErrorText('nope')).toBe('完整度评分暂时读不出来，请稍后重试');
    });
  });
});
