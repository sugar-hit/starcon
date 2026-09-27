'use client';

import { cn } from '../../lib/utils';

interface CityBackdropProps {
  /** 照片 URL */
  url: string;
  className?: string;
}

/**
 * 城市地标照片层:铺满整格,object-fit cover,底部对齐让地标贴近地平线。
 *
 * 纯视觉层,无障碍上走 aria-hidden;城市身份由旁边的文字标签负责。
 *
 * **为什么是"文字区压深板"而不是"按照片亮度换墨色"**:
 * 早期版本用 useAutoInk 读 canvas 像素决定深墨/浅墨,实测六张地标照失败:
 *   - 文字区实际亮度(按 object-fit:cover + bottom 对齐反推原图坐标采样)为
 *     0.453 / 0.658 / 0.434 / 0.545 / 0.286 / 0.246 —— 北京与伦敦落在中灰区,
 *     没有哪个阈值能同时判对;
 *   - 而且浏览器 taint canvas 后采样直接失败,回退成全部深墨。
 * 这六张横跨夜景(日落/蓝调/正午),亮度分布本身就没有共同规律。
 *
 * 所以改为不对抗照片:文字区(顶部 0~80%)铺一层中性深色渐变板,
 * 墨色恒定为浅色,照片本体从底部 20% 往上透出。
 * 判据从"照片亮度"换成"文字区底色恒定",对比度可推理、可测试、不受图源影响。
 */
export function CityBackdrop({ url, className }: CityBackdropProps) {
  return (
    <span
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={url}
        alt=""
        decoding="async"
        loading="lazy"
        className="absolute inset-0 h-full w-full object-cover"
        style={{ objectPosition: 'center bottom' }}
      />
      {/*
        文字区深色板:0% 处 rgba(11,16,32,.82) 压到最实,保证浅色读数可读;
        80% 后快速淡出到 0,让地标主体(照片最精彩的部分)完整露出。
        不用纯黑是为了保留一点照片冷调,避免看起来像加了灰块。
      */}
      <span
        className="absolute inset-0"
        style={{
          background:
            'linear-gradient(to bottom, rgba(11,16,32,0.82) 0%, rgba(11,16,32,0.78) 55%, rgba(11,16,32,0.45) 80%, rgba(11,16,32,0) 100%)',
        }}
      />
    </span>
  );
}
