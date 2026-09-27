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
 * 这是纯视觉层,无障碍上走 aria-hidden;城市身份由旁边的文字标签负责。
 *
 * 文字区压暗罩:随机实景图(picsum 占位)亮区不可控,地标图(CDN)
 * 才是"天空在上/地标在下"——后者可以让 auto-ink 决策生效,前者必须用
 * 静态压暗罩保证文字对比度。组件里现在用文字色 + 压暗罩两条路并行,
 * CDN 上线后关掉压暗罩、放开 auto-ink 颜色分支即可。
 */
export function CityBackdrop({ url, className }: CityBackdropProps) {
  return (
    <span
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      {/* crossOrigin 故意去掉:浏览器显示图片不需要 CORS,只有 canvas 读像素才需要。
          等 CDN 上线后,如要 auto-ink 自动采样像素,CDN 必须回 ACAO:*。 */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={url}
        alt=""
        decoding="async"
        loading="lazy"
        className="absolute inset-0 h-full w-full object-cover"
        style={{ objectPosition: 'center bottom' }}
      />
      {/* 压暗罩:顶到底从 0.42 渐变到 0。
          关键:不要上锁品牌色相,让照片本色透出。文字区(顶部 38%)始终低于
          rgba(0,0,0,0.25),白字对比度足够。 */}
      <span
        className="absolute inset-0"
        style={{
          background: 'linear-gradient(to bottom, rgba(0,0,0,0.42) 0%, rgba(0,0,0,0.30) 38%, rgba(0,0,0,0) 72%)',
        }}
      />
    </span>
  );
}
