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

test('every clock carries a landmark skyline in its own city shape', async ({ page }) => {
  const skylines = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      // 剪影是氛围层，走 aria-hidden 的 span
      const layer = card.querySelector('span[aria-hidden="true"] > svg[preserveAspectRatio]')!;
      const path = layer.querySelector('path')!;
      return {
        d: path.getAttribute('d') ?? '',
        // slice 才是等比；none 会把地标压变形（圆变椭圆、塔变矮）
        preserve: layer.getAttribute('preserveAspectRatio'),
        commands: (path.getAttribute('d') ?? '').split(/[A-Z]/).filter(Boolean).length,
      };
    }),
  );

  const shapes = new Set<string>();
  for (const skyline of skylines) {
    expect(skyline.d.length, '剪影路径不应为空').toBeGreaterThan(20);
    expect(skyline.commands, '剪影应由多个图元组成').toBeGreaterThan(2);
    expect(skyline.preserve, '剪影必须等比缩放，不能拉伸').toBe('xMidYMax slice');
    shapes.add(skyline.d);
  }
  // 六格六个城市，地标不应重复
  expect(shapes.size).toBe(6);
});

test('ink follows the local sky so night cities read light on dark', async ({ page }) => {
  // 对比度必须在浏览器侧算：getComputedStyle 的结果只存在于页面上下文
  const states = await cards(page).evaluateAll((els) => {
    const parse = (value: string) => {
      const parts = (value.match(/[\d.]+/g) ?? []).map(Number);
      return { r: parts[0] ?? 0, g: parts[1] ?? 0, b: parts[2] ?? 0 };
    };
    const channel = (value: number) => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (c: { r: number; g: number; b: number }) =>
      0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);

    return els.map((card) => {
      const time = card.querySelector('time')!;
      const ink = card.getAttribute('data-sky-ink');
      const fg = parse(getComputedStyle(time).color);
      const bg = parse(getComputedStyle(card).backgroundColor);
      const l1 = luminance(fg);
      const l2 = luminance(bg);
      return {
        ink,
        timeColor: getComputedStyle(time).color,
        background: getComputedStyle(card).backgroundColor,
        ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05),
        // 用于验证 ink 判定方向：底色亮度（0–255 感知加权）
        backgroundLuma: 0.2126 * bg.r + 0.7152 * bg.g + 0.0722 * bg.b,
      };
    });
  });

  for (const state of states) {
    expect(state.ink, '每格都应判定出 ink 模式').toMatch(/light|dark/);

    // 无论昼夜，文字与自身底色的对比度都要达到 WCAG AA 正文标准
    expect(
      state.ratio,
      `文字 ${state.timeColor} 对底色 ${state.background} 对比不足`,
    ).toBeGreaterThan(4.5);

    // 判定确实跟着底色走：浅墨配深底，深墨配浅底
    if (state.ink === 'light') {
      expect(state.backgroundLuma, '浅墨必须配深底').toBeLessThan(160);
    } else {
      expect(state.backgroundLuma, '深墨必须配浅底').toBeGreaterThan(160);
    }
  }
});
