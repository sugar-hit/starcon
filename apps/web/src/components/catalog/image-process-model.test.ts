import { describe, expect, it } from 'vitest';
import {
  JOB_STATUSES,
  MAX_ASSETS_PER_JOB,
  assetsSizeProblem,
  backgroundFormatProblem,
  defaultRatios,
  emptyRecipeInput,
  itemErrorText,
  itemStatusLabel,
  jobEmptyHint,
  jobStatusLabel,
  jobStatusTone,
  outputDimensionText,
  pollable,
  progressPercent,
  progressText,
  ratioLabelProblem,
  ratiosProblem,
  recipeCodeProblem,
  recipeEmptyHint,
  recipeNameProblem,
  recipeProblem,
  recipeSummary,
  submittedHint,
  targetFormatProblem,
  targetSizeProblem,
  terminal,
  versionRelationText,
} from './image-process-model';

/**
 * 3.4.1.2 图片处理前端模型单测。
 *
 * <p>重点覆盖三处最容易在界面上出错的地方：
 * 一是**进度语义**（分母为 0 不能算出 NaN；终态不能再轮询，否则页面变成永不停止的请求循环），
 * 二是**去背景与输出格式的组合**（只有 png 有 alpha，jpeg/bmp 会被渲染成黑块），
 * 三是**「原图始终保留」要讲清楚**（不写清楚，运营以为原图被覆盖就会再传一遍）。
 */
describe('image-process-model', () => {
  describe('词表与状态', () => {
    it('任务与条目状态都有中文标签，未知值原样返回', () => {
      expect(jobStatusLabel('PENDING')).toBe('排队中');
      expect(jobStatusLabel('PARTIAL')).toBe('部分失败');
      expect(jobStatusLabel('WEIRD')).toBe('WEIRD');
      expect(itemStatusLabel('SKIPPED')).toBe('已跳过');
      expect(itemStatusLabel(null)).toBe('未知');
      // 词表里每个状态都有标签，不给「未知」
      for (const s of JOB_STATUSES) expect(jobStatusLabel(s.value)).not.toBe(s.value);
    });

    it('终态与后端一致：终态不再轮询，只有排队与处理中才轮询', () => {
      for (const s of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']) {
        expect(terminal(s)).toBe(true);
        // 终态还轮询 = 永不停止的请求循环
        expect(pollable(s)).toBe(false);
      }
      expect(terminal('PENDING')).toBe(false);
      expect(pollable('PENDING')).toBe(true);
      expect(pollable('RUNNING')).toBe(true);
      expect(terminal(null)).toBe(false);
      expect(pollable(null)).toBe(false);
    });

    it('状态色调用 UI 包的 tone 词表，部分失败是警告而不是失败', () => {
      expect(jobStatusTone('SUCCEEDED')).toBe('success');
      expect(jobStatusTone('FAILED')).toBe('danger');
      // 部分失败不是「全失败」：给它 danger 会让运营以为要重跑整批
      expect(jobStatusTone('PARTIAL')).toBe('warning');
      expect(jobStatusTone('RUNNING')).toBe('neutral');
      expect(jobStatusTone('PENDING')).toBe('neutral');
    });
  });

  describe('进度', () => {
    it('分母为 0 时返回 0 而不是 NaN', () => {
      expect(
        progressPercent({ assetCount: 0, succeededCount: 0, failedCount: 0, skippedCount: 0 }),
      ).toBe(0);
      expect(
        progressText({ assetCount: 0, succeededCount: 0, failedCount: 0, skippedCount: 0 }),
      ).toBe('0 / 0');
    });

    it('进度按已完成条目算，计数讲成一句人话', () => {
      const job = { assetCount: 10, succeededCount: 2, failedCount: 1, skippedCount: 0 };
      expect(progressPercent(job)).toBe(30);
      expect(progressText(job)).toBe('3 / 10 · 成功 2 · 失败 1');
      // 跳过的条目也算「处理完了」，否则进度条永远不满
      const skipped = { assetCount: 4, succeededCount: 1, failedCount: 0, skippedCount: 2 };
      expect(progressPercent(skipped)).toBe(75);
      expect(progressText(skipped)).toBe('3 / 4 · 成功 1 · 跳过 2');
      // 没有失败/跳过时不显示那两段
      expect(
        progressText({ assetCount: 2, succeededCount: 2, failedCount: 0, skippedCount: 0 }),
      ).toBe('2 / 2 · 成功 2');
    });
  });

  describe('去背景与输出格式', () => {
    it('只有 png 能单独去背景，jpeg/bmp/gif 必须配合白底', () => {
      expect(backgroundFormatProblem(true, 'png')).toBeNull();
      // 开了白底（先抠后铺白）时任何格式都行
      expect(backgroundFormatProblem(true, 'jpeg')).not.toBeNull();
      expect(backgroundFormatProblem(true, 'jpeg')).toContain('png');
      expect(backgroundFormatProblem(true, 'jpeg')).toContain('白底');
      expect(backgroundFormatProblem(true, 'bmp')).not.toBeNull();
      expect(backgroundFormatProblem(true, 'gif')).not.toBeNull();
      // 不去背景时没有这个约束
      expect(backgroundFormatProblem(false, 'jpeg')).toBeNull();
    });

    it('WEBP 目标格式被拒并说明原因', () => {
      expect(targetFormatProblem('webp')).toContain('ImageIO');
      expect(targetFormatProblem('png')).toBeNull();
      expect(targetFormatProblem('JPEG')).toBeNull();
      expect(targetFormatProblem('')).toBe('目标格式不能为空');
    });
  });

  describe('配方校验', () => {
    it('编码与名称按字典口径校验', () => {
      expect(recipeCodeProblem('amazon-main')).toBeNull();
      expect(recipeCodeProblem('_leading')).toContain('英文字母');
      expect(recipeCodeProblem('')).toBe('配方编码不能为空');
      expect(recipeCodeProblem('x'.repeat(33))).toContain('32');
      expect(recipeNameProblem('')).toBe('配方名称不能为空');
    });

    it('目标尺寸要么两个都给，要么都不给', () => {
      expect(targetSizeProblem(null, null)).toBeNull();
      expect(targetSizeProblem(100, 100)).toBeNull();
      expect(targetSizeProblem(100, null)).toContain('同时给宽和高');
      expect(targetSizeProblem(null, 100)).toContain('同时给宽和高');
      expect(targetSizeProblem(0, 100)).toContain('大于 0');
      expect(targetSizeProblem(13000, 100)).toContain('12000');
    });

    it('比例档标签在配方内唯一，null 档被安全跳过而不是崩掉', () => {
      expect(ratiosProblem([{ label: 'MAIN', widthPx: 10, heightPx: 10 }])).toBeNull();
      expect(
        ratiosProblem([
          { label: 'MAIN', widthPx: 10, heightPx: 10 },
          { label: 'main', widthPx: 20, heightPx: 20 },
        ]),
      ).toContain('MAIN');
      expect(ratiosProblem(null)).toBeNull();
      expect(ratiosProblem([null])).toBeNull();
      expect(ratiosProblem([{ label: '', widthPx: 10, heightPx: 10 }])).toContain('不能为空');
      expect(ratiosProblem([{ label: 'MAIN', widthPx: 0, heightPx: 10 }])).toContain('大于 0');
      // 超过 8 档
      expect(
        ratiosProblem(
          Array.from({ length: 9 }, (_, i) => ({ label: `R${i}`, widthPx: 10, heightPx: 10 })),
        ),
      ).toContain('8');
    });

    it('整体配方校验把各条规则串起来，返回第一条可执行原因', () => {
      // 用一份「除编码外都合法」的基线：空表单的编码本来就是空的，
      // 直接拿去断言 recipeProblem 为 null 会先撞上「编码不能为空」。
      const base = { ...emptyRecipeInput(), code: 'AMZ-MAIN', name: '亚马逊主图' };
      expect(recipeProblem(base)).toBeNull();
      // 去背景 + jpeg 且没开白底
      expect(recipeProblem({ ...base, removeBackground: true, targetFormat: 'jpeg' })).toContain(
        '白底',
      );
      // 编码非法
      expect(recipeProblem({ ...base, code: '_x' })).toContain('英文字母');
      // 只给宽不给高
      expect(recipeProblem({ ...base, targetWidthPx: 100 })).toContain('同时给宽和高');
    });

    it('容差与不透明度都有区间', () => {
      // 同样要给合法编码，否则报回来的是编码那条原因，测不到区间。
      const base = { ...emptyRecipeInput(), code: 'AMZ-MAIN', name: '亚马逊主图' };
      expect(recipeProblem({ ...base, backgroundTolerance: 256 })).toContain('0~255');
      expect(recipeProblem({ ...base, backgroundTolerance: -1 })).toContain('0~255');
      expect(recipeProblem({ ...base, watermarkOpacity: 101 })).toContain('0~100');
      expect(ratioLabelProblem('x'.repeat(33))).toContain('32');
    });
  });

  describe('批量上限', () => {
    it('单次批量有上限，前后端同一口径', () => {
      expect(MAX_ASSETS_PER_JOB).toBe(500);
      expect(assetsSizeProblem(0)).toContain('至少选择一个');
      expect(assetsSizeProblem(1)).toBeNull();
      expect(assetsSizeProblem(500)).toBeNull();
      expect(assetsSizeProblem(501)).toContain('500');
    });
  });

  describe('文案', () => {
    it('提交提示说清「异步受理」与「原图保留」', () => {
      const hint = submittedHint({
        id: 'a',
        recipeId: 'b',
        recipeName: 'c',
        status: 'PENDING',
        statusLabel: '排队中',
        assetCount: 12,
        itemCount: 12,
        error: '',
        cancelRequested: false,
        note: '',
      });
      // 不写「排队」用户会以为已经处理完了
      expect(hint).toContain('排队');
      expect(hint).toContain('12');
      // 不写「原图保留」用户会再传一遍，素材库里就多一份重复内容
      expect(hint).toContain('原图');
      expect(hint).toContain('新版本');
    });

    it('版本关系讲清原图仍在历史里', () => {
      const text = versionRelationText(1, 2);
      expect(text).toContain('v1');
      expect(text).toContain('v2');
      expect(text).toContain('保留');
    });

    it('失败原因优先用后端给的中文，缺失时兜底', () => {
      expect(itemErrorText({ status: 'FAILED', error: '读取原图失败（键 xxx）' })).toContain(
        '读取原图失败',
      );
      expect(itemErrorText({ status: 'FAILED', error: '' })).toContain('没有给出原因');
      expect(itemErrorText({ status: 'CANCELLED', error: null })).toContain('取消');
      // 成功没有原因可显示
      expect(itemErrorText({ status: 'SUCCEEDED', error: '' })).toBeNull();
    });

    it('空态文案区分「筛选后为空」与「还没开始」', () => {
      expect(jobEmptyHint(true)).toContain('筛选条件');
      expect(jobEmptyHint(false)).toContain('还没有');
      expect(recipeEmptyHint()).toContain('配方');
    });

    it('配方摘要把六项能力讲成人话', () => {
      const text = recipeSummary({
        targetFormat: 'png',
        removeBackground: true,
        whiteBackground: true,
        watermarkText: 'SHOP',
        ratios: [{ label: 'MAIN' }, { label: 'THUMBNAIL' }],
      });
      expect(text).toContain('去背景');
      expect(text).toContain('白底');
      expect(text).toContain('SHOP');
      expect(text).toContain('PNG');
      expect(text).toContain('MAIN + THUMBNAIL');
      // 没有水印、比例为空时不显示空段落
      expect(
        recipeSummary({
          targetFormat: 'jpeg',
          removeBackground: false,
          whiteBackground: false,
          watermarkText: null,
          ratios: null,
        }),
      ).toBe('输出 JPEG');
    });

    it('尺寸空值显示「未探测」而不是 0', () => {
      expect(outputDimensionText(120, 80)).toBe('120 × 80');
      expect(outputDimensionText(null, null)).toBe('未探测');
      expect(outputDimensionText(null, 80)).toBe('未探测');
    });

    it('默认比例档是蓝图点名的「主图 1:1 + 缩略图」', () => {
      const ratios = defaultRatios();
      expect(ratios.map((r) => r.label)).toEqual(['MAIN', 'THUMBNAIL']);
      // 开着 noUncheckedIndexedAccess，所以用解构取出再断言，不直接下标访问
      const [main, thumb] = ratios;
      expect(main).toBeDefined();
      expect(thumb).toBeDefined();
      expect(main?.widthPx).toBe(main?.heightPx);
      expect(thumb?.widthPx).toBe(thumb?.heightPx);
      expect(ratiosProblem(ratios)).toBeNull();
    });
  });
});
