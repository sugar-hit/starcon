import { expect, test, type Page } from '@playwright/test';

const ROTATION_WAIT = 15_000;

function clocks(page: Page) {
  return page.getByRole('region', { name: '全球时间' });
}
function cards(page: Page) {
  return clocks(page).locator(':scope > div');
}
function rotatingCards(page: Page) {
  return clocks(page).locator(':scope > div[data-rotating="true"]');
}
function cardLabels(page: Page) {
  return cards(page).evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')));
}
test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
});

test('every clock carries a globe drawn for its own city', async ({ page }) => {
  await expect(cards(page).locator('svg[role="img"]')).toHaveCount(6);

  const globes = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const svg = card.querySelector('svg[role="img"]')!;
      const land = svg.querySelector('g[clip-path] path')!;
      const grid = svg.querySelectorAll('g[clip-path] path')[1]!;
      const box = card.getBoundingClientRect();
      const g = svg.getBoundingClientRect();
      return {
        label: card.getAttribute('aria-label'),
        title: svg.getAttribute('aria-label'),
        landPoints: (land.getAttribute('d') || '').split('M').filter(Boolean).length,
        hasGrid: (grid.getAttribute('d') || '').length > 40,
        hasPin: !!svg.querySelector('g[opacity]'),
        onRight: g.left + g.width / 2 > box.left + box.width / 2,
        onTop: g.top + g.height / 2 < box.top + box.height / 2,
        size: Math.round(g.width),
      };
    }),
  );

  for (const globe of globes) {
    expect(globe.title, globe.label).toContain('在地球上的位置');
    expect(globe.landPoints, `${globe.label} 应有大陆`).toBeGreaterThan(2);
    expect(globe.hasGrid, `${globe.label} 应有经纬网`).toBe(true);
    expect(globe.hasPin, `${globe.label} 应有定位点`).toBe(true);
    expect(globe.onRight && globe.onTop, `${globe.label} 地球应在右上角`).toBe(true);
    expect(globe.size).toBeGreaterThanOrEqual(48);
  }
});

test('each globe faces a different part of the world', async ({ page }) => {
  const outlines = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('g[clip-path] path')!.getAttribute('d')),
  );
  expect(new Set(outlines).size).toBe(6);
});

test('the five regional clocks rotate while the local one stays', async ({ page }) => {
  await expect(rotatingCards(page)).toHaveCount(5);
  const before = await cardLabels(page);
  expect(before).toHaveLength(6);
  expect(new Set(before).size).toBe(6);

  await expect
    .poll(async () => (await cardLabels(page)).slice(1), { timeout: ROTATION_WAIT })
    .not.toEqual(before.slice(1));

  const after = await cardLabels(page);
  expect(after[0]).toBe(before[0]);
  expect(new Set(after).size).toBe(6);
  for (const tooltip of await cards(page).evaluateAll((els) =>
    els.map((el) => el.getAttribute('title') || ''),
  )) {
    expect(tooltip === '' || /轮换/.test(tooltip)).toBe(true);
  }
});

test('each regional globe actually turns instead of jumping', async ({ page }) => {
  const card = cards(page).nth(1);
  const read = () => card.locator('svg[role="img"] g[clip-path] path').first().getAttribute('d');
  await expect(card).toHaveAttribute('data-switches', '1', { timeout: ROTATION_WAIT });

  // 换场后连续采样：补间应当逐帧改变轮廓，而不是一次跳完
  const seen = new Set<string>();
  for (let frame = 0; frame < 20; frame += 1) {
    seen.add((await read()) || '');
    await page.waitForTimeout(80);
  }
  expect(seen.size, '旋转过程应产生多帧不同的地球轮廓').toBeGreaterThan(6);
});

test('all six clocks stay distinct while rotating', async ({ page }) => {
  for (let sample = 0; sample < 14; sample += 1) {
    expect(new Set(await cardLabels(page)).size).toBe(6);
    await page.waitForTimeout(1000);
  }
});

test('every clock colon breathes while the digits stay still', async ({ page }) => {
  const colons = clocks(page).locator('[data-clock-colon]');
  await expect(colons).toHaveCount(6);

  const first = colons.first();
  const style = await first.evaluate((el) => {
    const cs = getComputedStyle(el);
    return {
      name: cs.animationName,
      duration: Number.parseFloat(cs.animationDuration),
      iteration: cs.animationIterationCount,
      text: el.textContent,
    };
  });
  expect(style.name).toBe('clock-breathe');
  expect(style.iteration).toBe('infinite');
  expect(style.text).toBe(':');
  // 呼吸要慢：单程至少 1 秒，不能像闪烁
  expect(style.duration).toBeGreaterThanOrEqual(1);
  expect(style.duration).toBeLessThanOrEqual(4);

  const opacities = new Set<number>();
  for (let frame = 0; frame < 10; frame += 1) {
    opacities.add(
      await first.evaluate((el) => Math.round(Number(getComputedStyle(el).opacity) * 20) / 20),
    );
    await page.waitForTimeout(200);
  }
  expect(opacities.size, '冒号透明度应随时间变化').toBeGreaterThan(3);
  // 暗到头也要看得见，不能消失
  expect(Math.min(...opacities)).toBeGreaterThanOrEqual(0.35);

  const times = clocks(page).locator('time');
  expect(await times.first().evaluate((el) => getComputedStyle(el).animationName)).toBe('none');
  expect((await times.first().textContent())?.trim()).toMatch(/^\d{2}:\d{2}$/);
});

test('rotation animates briefly and keeps the clock readable', async ({ page }) => {
  const rotating = rotatingCards(page).first();

  // 首屏不播入场动效
  expect(
    await rotating
      .locator('[data-clock-label]')
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe('none');

  await expect(rotating).toHaveAttribute('data-switches', '1', { timeout: ROTATION_WAIT });
  await expect(rotating.locator('.clock-sweep')).toHaveCount(1);

  const seconds = Number.parseFloat(
    await rotating
      .locator('[data-clock-label]')
      .evaluate((el) => getComputedStyle(el).animationDuration),
  );
  expect(seconds).toBeGreaterThan(0.2);
  expect(seconds).toBeLessThanOrEqual(0.6);

  // 时间读数本身不参与动画，轮换时保持即时可读
  expect(await rotating.locator('time').evaluate((el) => getComputedStyle(el).animationName)).toBe(
    'none',
  );
});

test('cells either show their photo backdrop or fall back to a clean card', async ({ page }) => {
  const states = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const img = card.querySelector('img');
      return {
        label: card.getAttribute('aria-label') ?? '',
        hasPhoto: card.hasAttribute('data-has-photo'),
        // 照片层走 aria-hidden 的 span 包裹 img
        imgInBackdrop: card.querySelector('span[aria-hidden="true"] img') !== null,
        imgSrc: img?.getAttribute('src') ?? null,
        ink: card.getAttribute('data-ink'),
      };
    }),
  );

  for (const state of states) {
    expect(state.hasPhoto, `${state.label} data-has-photo 必须存在`).toBe(true);
    if (state.hasPhoto) {
      expect(state.imgInBackdrop, `${state.label} 有照片必须渲染 img`).toBe(true);
      expect(state.imgSrc, `${state.label} img 必须有 src`).not.toBeNull();
      // 同一卡片有照片时,ink 必须存在
      expect(state.ink, `${state.label} 照片格必须有 ink 判定`).toMatch(/light|dark/);
    } else {
      expect(state.imgInBackdrop, `${state.label} 无照片不应渲染 img`).toBe(false);
      expect(state.ink, `${state.label} 无照片格 ink 必须为 dark`).toBe('dark');
    }
  }
});

test('photo-backed cells keep the time readable above the backdrop', async ({ page }) => {
  // 回归:照片层是 position:absolute,时间/日期原本是 position:static,
  // 同一 stacking context 里绝对定位永远压在静态流之上 → 时间数字被整层盖住。
  // 这里用 elementFromPoint 验证时间中心点命中的就是 <time> 本身,不是照片层。
  const checks = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const time = card.querySelector('time')!;
      const r = time.getBoundingClientRect();
      const cx = r.x + r.width / 2;
      const cy = r.y + r.height / 2;
      const hit = document.elementFromPoint(cx, cy);
      const onTop = hit === time || time.contains(hit);
      return {
        label: card.getAttribute('aria-label'),
        hasPhoto: card.hasAttribute('data-has-photo'),
        onTop,
        hitTag: hit?.tagName ?? null,
        text: time.textContent?.trim() ?? '',
      };
    }),
  );

  for (const check of checks) {
    expect(check.text, `${check.label} 应有读数`).toMatch(/^\d{2}:\d{2}$/);
    if (!check.hasPhoto) continue;
    expect(
      check.onTop,
      `${check.label} 时间被压在照片层下（命中 ${check.hitTag}），读数不可见`,
    ).toBe(true);
  }
});

test('photo-backed cells use a fixed-light ink regardless of placeholder luminance', async ({ page }) => {
  // 当前阶段用 picsum 随机实景照占位,亮区不可控,auto-ink 采样会误判。
  // 组件采用静态压暗罩 + 固定白字兜底;真实地标照(天空在上/地标在下)接入后
  // 重新启用 useAutoInk 颜色分支,届时改这条断言为采样"照片+罩"合成再判断。
  const states = await cards(page).evaluateAll((els) =>
    els
      .filter((card) => card.hasAttribute('data-has-photo'))
      .map((card) => {
        const time = card.querySelector('time')!;
        const cs = getComputedStyle(time);
        return { timeColor: cs.color, ink: card.getAttribute('data-ink') };
      }),
  );

  expect(states.length, '应有照片格被采样').toBeGreaterThan(0);
  for (const state of states) {
    // 占位阶段统一白字;CDN 上线后,这里按 ink 期望 dark/light 文字色
    expect(state.timeColor, '照片格当前统一走白字').toBe('rgb(255, 255, 255)');
  }
});
