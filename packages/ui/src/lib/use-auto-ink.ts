'use client';

import { useEffect, useState } from 'react';

export type AutoInk = 'light' | 'dark';

/**
 * 运行时读取图片亮度,推导文字墨色。
 *
 * 把图片画到小尺寸 canvas,采样**顶部区域**(文字所在)像素,
 * 算加权亮度 → 高于阈值用深墨、低于用浅墨。
 *
 * 设计取向:
 * - 只在 mount 与 url 变化时跑一次,后续不重读。
 * - canvas 用 48×27 极小尺寸,六张图合计 < 2ms。
 * - crossOrigin="anonymous",要求 CDN 必须回 ACAO:*;否则 fallback 到默认深墨。
 * - 图片加载失败也 fallback,不抛错。
 */
export function useAutoInk(
  url: string | undefined,
  options: { sampleTopRatio?: number; threshold?: number } = {},
): AutoInk {
  const { sampleTopRatio = 0.55, threshold = 0.55 } = options;
  const [ink, setInk] = useState<AutoInk>('dark');

  useEffect(() => {
    if (!url) {
      setInk('dark');
      return;
    }
    let cancelled = false;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.decoding = 'async';
    img.onload = () => {
      if (cancelled) return;
      try {
        const sample = sampleTopLuminance(img, sampleTopRatio);
        setInk(sample >= threshold ? 'dark' : 'light');
      } catch {
        setInk('dark');
      }
    };
    img.onerror = () => {
      if (cancelled) return;
      setInk('dark');
    };
    img.src = url;
    return () => {
      cancelled = true;
    };
  }, [url, sampleTopRatio, threshold]);

  return ink;
}

/**
 * 取图片顶部区域的平均感知亮度(0–1)。
 * 暴露出来给单测用,不参与组件渲染。
 */
export function sampleTopLuminance(
  source: CanvasImageSource & { naturalWidth: number; naturalHeight: number },
  topRatio: number,
): number {
  const sampleW = 48;
  const sampleH = Math.max(
    1,
    Math.round((sampleW * source.naturalHeight) / source.naturalWidth),
  );
  const canvas = document.createElement('canvas');
  canvas.width = sampleW;
  canvas.height = sampleH;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return 1;
  ctx.drawImage(source, 0, 0, sampleW, sampleH);
  const rows = Math.max(1, Math.round(sampleH * topRatio));
  const data = ctx.getImageData(0, 0, sampleW, rows).data;
  let sum = 0;
  let count = 0;
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i] ?? 0;
    const g = data[i + 1] ?? 0;
    const b = data[i + 2] ?? 0;
    sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
    count += 1;
  }
  return count === 0 ? 1 : sum / count / 255;
}
