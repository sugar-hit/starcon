import { describe, expect, it } from 'vitest';
import type { MediaDetail, MediaRow } from '../../../../../packages/api-contracts/src/index';
import {
  INTEGRITY_LABELS,
  byteSizeText,
  dimensionText,
  durationText,
  emptyHint,
  emptyTagInput,
  emptyUploadInput,
  fileProblem,
  integrityAction,
  integrityFailing,
  integrityLabel,
  integrityTone,
  mediaTypeLabel,
  purposeLabel,
  tagCodeProblem,
  tagsProblem,
  toMediaUpdate,
  uploadProblem,
} from './media-model';

/**
 * 3.4.1.1 媒体库前端模型单测。
 *
 * <p>重点覆盖两类容易在界面上出错的判断：
 * 一是**完整性状态到「该做什么」的映射**（四种坏法处理动作不同，不能都写成「素材异常」），
 * 二是**「没探测」与「0」的区分**（视频宽高与时长是没探测，显示成 0 会误导运营）。
 */
describe('media-model', () => {
  describe('词表与标签', () => {
    it('媒体类型与用途都有中文标签', () => {
      expect(mediaTypeLabel('IMAGE')).toBe('图片');
      expect(mediaTypeLabel('VIDEO')).toBe('视频');
      expect(mediaTypeLabel(null)).toBe('未知');
      expect(mediaTypeLabel('WEIRD')).toBe('WEIRD');
      expect(purposeLabel('PRIMARY')).toBe('主图');
      expect(purposeLabel('DETAIL')).toBe('详情图');
    });

    it('完整性状态区分「未校验」与四种坏法，每种都给可执行的下一步', () => {
      expect(integrityLabel('UNKNOWN')).toBe('未校验');
      expect(integrityLabel('VERIFIED')).toBe('已校验');
      expect(integrityLabel('MISSING')).toBe('文件缺失');
      expect(integrityLabel('SIZE_MISMATCH')).toBe('大小不符');
      expect(integrityLabel('CONTENT_MISMATCH')).toBe('内容不符');
      // 词表里每个状态都有中文标签，不给「未知」
      for (const status of Object.keys(INTEGRITY_LABELS))
        expect(integrityLabel(status)).not.toBe(status);
      // 四种坏法的处理动作各不相同：都写成「素材异常」的话运维不知道该做什么
      const actions = ['MISSING', 'SIZE_MISMATCH', 'CONTENT_MISMATCH'].map((s) =>
        integrityAction(s),
      );
      expect(new Set(actions).size).toBe(3);
      for (const action of actions) expect(action).toBeTruthy();
      // 正常与未校验不需要动作
      expect(integrityAction('VERIFIED')).toBeNull();
      expect(integrityAction('UNKNOWN')).toBeNull();
    });

    it('完好状态不算「有问题」', () => {
      expect(integrityFailing('VERIFIED')).toBe(false);
      expect(integrityFailing('UNKNOWN')).toBe(false);
      expect(integrityFailing('MISSING')).toBe(true);
      expect(integrityFailing('SIZE_MISMATCH')).toBe(true);
      expect(integrityFailing('CONTENT_MISMATCH')).toBe(true);
      expect(integrityTone('VERIFIED')).toBe('success');
      expect(integrityTone('UNKNOWN')).toBe('neutral');
      expect(integrityTone('MISSING')).toBe('danger');
    });
  });

  describe('展示格式化', () => {
    it('字节数折算成人类可读', () => {
      expect(byteSizeText(512)).toBe('512 B');
      expect(byteSizeText(2048)).toBe('2.0 KB');
      expect(byteSizeText(5 * 1024 * 1024)).toBe('5.0 MB');
      expect(byteSizeText(3 * 1024 * 1024 * 1024)).toBe('3.00 GB');
      expect(byteSizeText(null)).toBe('—');
    });

    it('「没探测」显示成未探测而不是 0', () => {
      // 视频宽高与时长是没探测。显示成 0 会让运营以为「时长 0 秒」而误判。
      expect(dimensionText(null, null)).toBe('未探测');
      expect(dimensionText(120, 80)).toBe('120 × 80');
      // 只有一半有值也算未探测：半截数字比空更误导
      expect(dimensionText(120, null)).toBe('未探测');
      expect(durationText(null)).toBe('未探测');
      expect(durationText(12)).toBe('12 秒');
    });
  });

  describe('校验', () => {
    it('标签编码按机器标识口径校验（中文留给名称）', () => {
      expect(tagCodeProblem('WHITE-BG')).toBeNull();
      expect(tagCodeProblem(' hero ')).toBeNull();
      expect(tagCodeProblem('')).toContain('不能为空');
      expect(tagCodeProblem('中文编码')).toContain('英文字母');
      expect(tagCodeProblem('A'.repeat(33))).toContain('32');
      expect(tagCodeProblem('BAD SPACE')).toContain('英文字母');
    });

    it('同一标签不能重复挂载', () => {
      expect(tagsProblem(['A', 'B'])).toBeNull();
      expect(tagsProblem([])).toBeNull();
      expect(tagsProblem(['white-bg', 'WHITE-BG'])).toContain('重复');
      expect(tagsProblem(Array.from({ length: 51 }, () => 'T'))).toContain('50');
    });

    it('上传前挡掉必然失败的情况，但不用扩展名当类型判据', () => {
      expect(fileProblem(null)).toContain('请选择');
      expect(fileProblem(new File([], 'a.png'))).toContain('空的');
      expect(fileProblem(new File([new Uint8Array(1)], 'huge.png'))).toBeNull();
      // WEBP 提前给可读原因：服务端读不出它的宽高
      expect(fileProblem(new File([new Uint8Array(1)], 'a.webp'))).toContain('WEBP');
      // 扩展名与真实类型不符不在这层判——那正是后端按字节签名判定的原因
      expect(fileProblem(new File([new Uint8Array(1)], 'a.txt'))).toBeNull();
    });

    it('整表校验返回第一条可执行原因', () => {
      expect(uploadProblem(emptyUploadInput())).toContain('名称');
      expect(uploadProblem({ ...emptyUploadInput(), name: 'x', mediaType: 'AUDIO' })).toContain(
        'IMAGE',
      );
      expect(
        uploadProblem({ ...emptyUploadInput(), name: 'x', mediaType: 'IMAGE', purpose: 'BANNER' }),
      ).toContain('用途');
      expect(
        uploadProblem({
          ...emptyUploadInput(),
          name: '白底主图',
          mediaType: 'IMAGE',
          purpose: 'PRIMARY',
          tagCodes: ['BAD SPACE'],
        }),
      ).toContain('英文字母');
      expect(
        uploadProblem({
          ...emptyUploadInput(),
          name: '白底主图',
          mediaType: 'IMAGE',
          purpose: 'PRIMARY',
        }),
      ).toBeNull();
    });
  });

  describe('表单初值', () => {
    it('空上传表单的默认值是安全的（图片 + 附图 + 启用）', () => {
      const input = emptyUploadInput();
      expect(input.mediaType).toBe('IMAGE');
      expect(input.purpose).toBe('GALLERY');
      expect(input.enabled).toBe(true);
      expect(input.tagCodes).toEqual([]);
      expect(emptyTagInput().version).toBe(0);
    });

    it('详情转表单：标签取编码，version 带过来做乐观锁', () => {
      const detail = {
        asset: row({
          name: '主图',
          purpose: 'PRIMARY',
          description: '',
          altText: '正面',
          version: 7,
        }),
        tags: { 'WHITE-BG': '白底', HERO: '主图' },
        versions: [],
        references: [],
        integrityChecks: [],
        deletable: true,
        deletableReason: null,
        note: '',
      } as unknown as MediaDetail;
      const update = toMediaUpdate(detail, Object.keys(detail.tags ?? {}));
      expect(update.name).toBe('主图');
      expect(update.purpose).toBe('PRIMARY');
      expect(update.tagCodes).toEqual(['WHITE-BG', 'HERO']);
      expect(update.version).toBe(7);
      // 空描述转成 null，不把空串提交回去
      expect(update.description).toBeNull();
    });
  });

  describe('空态文案', () => {
    it('无筛选与有筛选的空态文案不同', () => {
      expect(emptyHint(false)).toContain('还没有素材');
      expect(emptyHint(true)).toContain('筛选条件');
      expect(emptyHint(false)).not.toBe(emptyHint(true));
    });
  });
});

function row(overrides: Partial<MediaRow>): MediaRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    name: '素材',
    description: '',
    mediaType: 'IMAGE',
    mediaTypeLabel: '图片',
    purpose: 'GALLERY',
    purposeLabel: '附图',
    fileFormat: 'png',
    mimeType: 'image/png',
    byteSize: 1024,
    widthPx: 100,
    heightPx: 100,
    durationSeconds: null,
    contentSha256: 'a'.repeat(64),
    storageKey: 'media/t/x.png',
    originalFilename: 'x.png',
    altText: null,
    currentVersion: 1,
    versionCount: 1,
    referenceCount: 0,
    tagCodes: [],
    integrityStatus: 'UNKNOWN',
    integrityLabel: '未校验',
    integrityDetail: null,
    integrityCheckedAt: null,
    enabled: true,
    version: 0,
    createdAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}
