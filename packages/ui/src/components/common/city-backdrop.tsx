'use client';

import * as React from 'react';
import { cn } from '../../lib/utils';

interface CityBackdropProps {
  url: string;
  className?: string;
}

/** 交叉淡入时长，需与 tokens.css 里 .clock-photo-layer 的 transition 一致 */
const FADE_MS = 1600;

/**
 * 城市照片层：铺满整格，object-fit cover，底部对齐让地标贴近地平线。
 *
 * **为什么是双层交叉淡入而不是换 URL 直接替换**：
 * 单层做法在换城瞬间旧图消失、新图从 opacity 0 淡入，读起来是"咔嚓"一下；
 * 照片未命中缓存时新图还要等 decode，中间会直接露出底色。
 * 这里始终保留上一层，新层叠在上面淡入、旧层同步淡出，两张图在过渡期重叠，
 * 观感是溶解而不是切换。
 *
 * 过渡分两步驱动（否则挂载即终值，浏览器不会产生过渡）：
 *   1. 新 url 到达 → 旧层保持不透明，新层挂载但停在 opacity 0
 *   2. 新图 decode 完成 → 同一个 commit 里把新层推到 1、旧层推到 0，
 *      两层都已在 DOM 中，CSS transition 正常生效；超时后卸载旧层
 *
 * 仍保留解码门控：只有 decode 成功的新图才升到不透明，避免半张图；
 * 过期请求不能把上一个城市显示成当前城市。
 */
export function CityBackdrop({ url, className }: CityBackdropProps) {
  const imageRef = React.useRef<HTMLImageElement>(null);
  /** 已解码、当前显示的 url */
  const [shown, setShown] = React.useState<string | null>(null);
  /** 上一层，交叉淡出期间保留 */
  const [outgoing, setOutgoing] = React.useState<string | null>(null);
  /** 过渡是否已启动：false 时新层保持透明、旧层保持不透明 */
  const [fading, setFading] = React.useState(false);
  /** 供 effect 读取当前显示的 url，避免把 shown 塞进依赖导致循环 */
  const shownRef = React.useRef<string | null>(null);

  React.useEffect(() => {
    const image = imageRef.current;
    if (!image) return;

    let cancelled = false;
    let dropTimer = 0;
    let frame1 = 0;
    let frame2 = 0;

    // 复位：旧层不透明待命，新层先藏在下面
    setFading(false);
    setOutgoing(shownRef.current);

    image.decode().then(
      () => {
        if (cancelled) return;
        shownRef.current = url;
        setShown(url);
        // 双 rAF：确保"新层 opacity 0"这一初值已被提交，再启动过渡
        frame1 = requestAnimationFrame(() => {
          frame2 = requestAnimationFrame(() => setFading(true));
        });
        dropTimer = window.setTimeout(() => setOutgoing(null), FADE_MS);
      },
      () => {
        // 解码失败：既不显示半张图，也不回退到旧城市，保持空底
        if (cancelled) return;
        shownRef.current = null;
        setShown(null);
        setOutgoing(null);
      },
    );

    return () => {
      cancelled = true;
      cancelAnimationFrame(frame1);
      cancelAnimationFrame(frame2);
      window.clearTimeout(dropTimer);
    };
  }, [url]);

  return (
    <span
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}
    >
      {/* 旧层：与新层重叠直到过渡结束才卸载，避免中途露底 */}
      {outgoing && outgoing !== shown ? (
        <span
          className="clock-photo-layer absolute inset-0"
          data-photo-outgoing="true"
          style={{ opacity: fading ? 0 : 1 }}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={outgoing}
            alt=""
            decoding="async"
            loading="eager"
            className="absolute inset-0 h-full w-full object-cover"
            style={{ objectPosition: 'center bottom' }}
          />
        </span>
      ) : null}

      {/* 新层：decode 完成后淡入 */}
      <span
        className="clock-photo-layer absolute inset-0"
        data-photo-layer="true"
        style={{ opacity: shown === url && fading ? 1 : 0 }}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          key={url}
          ref={imageRef}
          src={url}
          alt=""
          decoding="async"
          loading="eager"
          data-photo-ready={shown === url}
          className="absolute inset-0 h-full w-full object-cover"
          style={{
            objectPosition: 'center bottom',
            visibility: shown === url ? 'visible' : 'hidden',
          }}
        />
      </span>

      {/* 文字区深色板：恒定压在最上层，两层照片都在它下面 */}
      <span
        className="absolute inset-0"
        style={{
          background:
            'linear-gradient(to bottom, rgba(11,16,32,0.82) 0%, rgba(11,16,32,0.78) 55%, rgba(11,16,32,0.72) 80%, rgba(11,16,32,0.65) 100%)',
        }}
      />
    </span>
  );
}
