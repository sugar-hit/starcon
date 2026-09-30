# 国际时间换场动效

日期：2026-09-30

## 决策摘要

- 城市照片保留解码完成后再显示的双层结构，交叉渐变由 700ms 放慢到 1.6 秒。
- 城市标签与日期/时差动效采用克制的机场/交易所字牌感：小幅纵向落位、10 度内透视和轻微模糊恢复，总时长 620ms。
- 元信息延后 70ms 进场，只形成轻微层次；时间数字不参与动画，保持阅读稳定。
- 北京保持固定，其余五个城市槽位的轮换周期由 15 秒缩短为 5 秒。
- 继续遵守 `prefers-reduced-motion`，减少动效模式下取消动画和过渡。

## 权衡

- 不实现字符逐个高频翻转，避免六格时钟同时换场时过于喧闹。
- 1.6 秒照片渐变后仍有约 3.4 秒稳定展示，在加快轮换的同时保留城市辨识时间。

## 文件清单

- `packages/ui/src/components/common/city-backdrop.tsx`
- `packages/ui/src/components/common/world-clock-strip.tsx`
- `packages/ui/src/lib/world-clock.ts`
- `packages/ui/src/lib/world-clock.test.ts`
- `packages/ui/src/styles/tokens.css`
- `tests/e2e/world-clock.spec.ts`
- `docs/design/visual-spec.md`
- `docs/design/world-clock-images.md`

## 后续待办

- 无。
