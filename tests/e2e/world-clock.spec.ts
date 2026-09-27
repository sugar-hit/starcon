import { expect, test, type Page } from '@playwright/test';

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

test('every clock carries a transparent line globe drawn for its city', async ({ page }) => {
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
        noSphereFill: [...svg.querySelectorAll('circle')]
          .filter((circle) => Number(circle.getAttribute('r')) > 10)
          .every((circle) => circle.getAttribute('fill') === 'none' || circle.closest('clipPath')),
        size: Math.round(g.width),
      };
    }),
  );

  for (const globe of globes) {
    expect(globe.title, globe.label).toContain('在地球上的位置');
    expect(globe.landPoints, `${globe.label} 应有大陆`).toBeGreaterThan(2);
    expect(globe.hasGrid, `${globe.label} 应有经纬网`).toBe(true);
    expect(globe.hasPin, `${globe.label} 应有定位点`).toBe(true);
    expect(globe.onRight, `${globe.label} 地球应在右侧`).toBe(true);
    expect(globe.noSphereFill, '球体无底色').toBe(true);
    expect(globe.size).toBeGreaterThanOrEqual(48);
  }
});

test('each globe faces a different part of the world', async ({ page }) => {
  const outlines = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('g[clip-path] path')!.getAttribute('d')),
  );
  expect(new Set(outlines).size).toBe(6);
});

test('Beijing stays fixed while all five regions rotate together', async ({ page }) => {
  await page.clock.install();
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
  const before = await cardLabels(page);
  await page.clock.runFor(15_100);
  const after = await cardLabels(page);
  expect(after[0]).toBe(before[0]);
  for (let index = 1; index < 6; index += 1) expect(after[index]).not.toBe(before[index]);
  await expect(rotatingCards(page)).toHaveCount(5);
  await expect(cards(page).first().locator('[data-clock-label]')).toHaveText('北京 中国（亚洲）');
  for (let index = 0; index < 6; index += 1) {
    const card = cards(page).nth(index);
    const label = await card.getAttribute('aria-label');
    const city = label!.split(' ')[0]!;
    await expect(card.getByRole('img')).toHaveAttribute('aria-label', city + '在地球上的位置');
    await expect(card.locator('time')).toHaveAttribute('aria-label', city + '时间');
  }
});

test('each initial city gets a distinct landmark photo', async ({ page }) => {
  await expect
    .poll(
      async () =>
        cards(page).evaluateAll(
          (els) =>
            els.map((el) => el.querySelector('img')?.naturalWidth ?? 0).filter(Boolean).length,
        ),
      { timeout: 15_000 },
    )
    .toBe(6);

  const photos = await cards(page).evaluateAll((els) =>
    els.map((el) => el.querySelector('span[aria-hidden="true"] img')?.getAttribute('src') ?? ''),
  );
  expect(
    photos.every((src) => src.length > 0),
    '六格都应配到照片',
  ).toBe(true);
  expect(new Set(photos).size, '六张照片应互不相同').toBe(6);
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

test('the reading itself stays instant while labels cross-fade on rotation', async ({ page }) => {
  // 读数(time)永远不参与动画，保证读秒即时准确；
  // 标签与元信息随换城做一次短淡入（clock-swap-in），这是用户要的平滑过渡。
  const names = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const nodes = [
        ['time', card.querySelector('time')],
        ['label', card.querySelector('[data-clock-label]')],
        ['meta', card.querySelector('[data-clock-meta]')],
      ] as const;
      return {
        label: card.getAttribute('aria-label'),
        anims: nodes
          .filter(([, node]) => node)
          .map(([name, node]) => [name, getComputedStyle(node!).animationName] as const),
      };
    }),
  );

  for (const card of names) {
    for (const [name, animation] of card.anims) {
      if (name === 'time') {
        expect(animation, `${card.label} 的读数不应有动画`).toBe('none');
      } else {
        // 首屏已播完，animation 保留在结束态；只要求它是 swap-in 而非 none
        expect(animation, `${card.label} 的 ${name} 应为换城淡入`).toBe('clock-swap-in');
      }
    }
  }
});

test('photo backdrop cross-fades instead of hard-swapping', async ({ page }) => {
  // 换城时新旧两层同时在场：新层淡入、旧层淡出，
  // 任何时刻都至少有一层不透明（不露底），过渡结束后旧层被卸载。
  const layers = await cards(page).evaluateAll((els) =>
    els.map((card) => {
      const node = card.querySelector('[data-photo-layer="true"]');
      const cs = node ? getComputedStyle(node) : null;
      return {
        label: card.getAttribute('aria-label'),
        hasNew: cs !== null,
        transition: cs?.transitionProperty ?? '',
        duration: cs?.transitionDuration ?? '',
      };
    }),
  );

  for (const layer of layers) {
    if (!layer.hasNew) continue;
    expect(layer.transition, `${layer.label} 新层应有 opacity 过渡`).toContain('opacity');
    const seconds = Number.parseFloat(layer.duration);
    expect(seconds, `${layer.label} 过渡时长应在 0.3~1.2s`).toBeGreaterThanOrEqual(0.3);
    expect(seconds).toBeLessThanOrEqual(1.2);
  }

  // 过渡过程中至少一帧两层同时可见（证明是交叉而非替换）
  const overlapping = await page.evaluate(async () => {
    const card = document.querySelector('section[aria-label="全球时间"] > div:nth-child(2)')!;
    const deadline = Date.now() + 20_000;
    while (Date.now() < deadline) {
      const out = card.querySelector('[data-photo-outgoing="true"]');
      const next = card.querySelector('[data-photo-layer="true"]');
      if (out && next) return true;
      await new Promise((r) => setTimeout(r, 50));
    }
    return false;
  });
  expect(overlapping, '换场时应存在旧层与新层重叠的交叉淡入帧').toBe(true);
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
        region: card.getAttribute('data-region'),
      };
    }),
  );

  for (const state of states) {
    expect(state.hasPhoto, `${state.label} data-has-photo 必须存在`).toBe(true);
    if (state.hasPhoto) {
      expect(state.imgInBackdrop, `${state.label} 有照片必须渲染 img`).toBe(true);
      expect(state.imgSrc, `${state.label} img 必须有 src`).not.toBeNull();
    } else {
      expect(state.imgInBackdrop, `${state.label} 无照片不应渲染 img`).toBe(false);
    }
  }

  // 第一格是本地时区（无区域标签），其余五格各带区域
  expect(states[0]?.region, '第一格应为本地时区').toBeNull();
  for (const state of states.slice(1)) {
    expect(state.region, `${state.label} 应有区域标签`).toBeTruthy();
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

test('photo-backed cells read light-on-dark against the fixed text plate', async ({ page }) => {
  // 照片亮度横跨夜景/日落/正午，没有共同规律（实测文字区亮度 0.246~0.658，
  // 北京与伦敦落在中灰区，任何阈值都判不对）。所以文字区底色改为恒定深色板，
  // 墨色固定浅色；这里断言文字确实是浅色，且背景确实被压到足够暗。
  const states = await cards(page).evaluateAll((els) =>
    els
      .filter((card) => card.hasAttribute('data-has-photo'))
      .map((card) => {
        const time = card.querySelector('time')!;
        const plate = card.querySelector('span[aria-hidden="true"] > span:last-child');
        return {
          label: card.getAttribute('aria-label'),
          timeColor: getComputedStyle(time).color,
          plate: plate ? getComputedStyle(plate).backgroundImage : '',
        };
      }),
  );

  expect(states.length, '应有照片格被采样').toBe(6);
  for (const state of states) {
    expect(state.timeColor, `${state.label} 应为浅色读数`).toBe('rgb(255, 255, 255)');
    // 深色板必须存在且顶部足够实（>= 0.7 不透明度）
    expect(state.plate, `${state.label} 应有文字区深色板`).toContain('linear-gradient');
    // 浏览器会把 rgba 序列化成带空格形式，正则需容忍
    const alphas = [
      ...state.plate.matchAll(/rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/g),
    ];
    expect(alphas.length, `${state.label} 深色板应有渐变停点`).toBeGreaterThan(0);
    const topAlpha = Number(alphas[0]?.[4] ?? '0');
    expect(topAlpha, `${state.label} 顶部遮罩应 >= 0.7`).toBeGreaterThanOrEqual(0.7);
  }
});

test('clock layout stays compact and footer slogan fits one line', async ({ page }, testInfo) => {
  const slogan = page.getByLabel('版权信息').locator('p').first();
  await expect(slogan).toHaveText('pioneering innovation.');
  expect(await slogan.evaluate((el) => el.getBoundingClientRect().height)).toBeLessThanOrEqual(16);
  await clocks(page)
    .locator('img')
    .evaluateAll(async (images) => {
      await Promise.all(
        images.map((image) => (image as HTMLImageElement).decode().catch(() => undefined)),
      );
    });
  for (const width of [1920, 1440, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    const layout = await cards(page).evaluateAll((els) =>
      els.map((card) => {
        const label = card.querySelector('[data-clock-label]')!.getBoundingClientRect();
        const globe = card.querySelector('svg')!.getBoundingClientRect();
        return {
          height: card.getBoundingClientRect().height,
          overlap: label.bottom > globe.top,
          labelOverflow:
            card.querySelector('[data-clock-label]')!.scrollWidth >
            card.querySelector('[data-clock-label]')!.clientWidth,
          overflow: card.scrollWidth > card.clientWidth,
        };
      }),
    );
    await page.screenshot({ path: testInfo.outputPath('clock-' + width + '.png') });
    for (const card of layout) {
      expect(card.height).toBeLessThanOrEqual(120);
      expect(card.overlap).toBe(false);
      expect(card.labelOverflow).toBe(false);
      expect(card.overflow).toBe(false);
    }
  }
});

test('photos stay hidden until decoding completes, then cross-fade in', async ({ page }) => {
  await page.addInitScript(() => {
    const original = HTMLImageElement.prototype.decode;
    const gate = new Promise<void>((resolve) => {
      window.addEventListener('release-photo-decode', () => resolve(), { once: true });
    });
    HTMLImageElement.prototype.decode = async function () {
      await original.call(this);
      this.setAttribute('data-decode-waiting', 'true');
      await gate;
    };
  });
  await page.goto('/');
  const photo = clocks(page).locator('img').first();
  await expect(photo).toHaveAttribute('data-decode-waiting', 'true', { timeout: 15000 });
  // 解码未完成时必须完全不可见，不能露出半张图
  await expect(photo).toHaveCSS('visibility', 'hidden');

  const layer = clocks(page).locator('[data-photo-layer="true"]').first();
  await expect(layer).toHaveCSS('opacity', '0');

  await page.evaluate(() => window.dispatchEvent(new Event('release-photo-decode')));
  await expect(photo).toHaveAttribute('data-photo-ready', 'true');
  await expect(photo).toHaveCSS('visibility', 'visible');
  // img 自身不参与动画，淡入由外层图层承担
  await expect(photo).toHaveCSS('animation-name', 'none');
  // 解码完成后图层应推到不透明（transition 进行中）
  await expect
    .poll(async () => Number.parseFloat(await layer.evaluate((n) => getComputedStyle(n).opacity)), {
      timeout: 5000,
    })
    .toBeGreaterThan(0.9);
});

test('failed photos leave readable clocks on a dark background', async ({ page }) => {
  await page.route('https://*.wikimedia.org/**', (route) => route.abort());
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
  for (const photo of await clocks(page).locator('img').all()) {
    await expect(photo).toHaveCSS('visibility', 'hidden');
  }
  for (const card of await cards(page).all()) {
    await expect(card).toHaveCSS('background-color', 'rgb(31, 41, 55)');
  }
});

test('a previous city decode cannot reveal a stale photo after rotation', async ({ page }) => {
  await page.addInitScript(() => {
    HTMLImageElement.prototype.decode = function () {
      this.setAttribute('data-decode-waiting', 'true');
      return new Promise<void>((resolve) => {
        window.addEventListener('release-old-decode', () => resolve(), { once: true });
      });
    };
  });
  await page.clock.install();
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
  const previous = await cards(page).nth(1).locator('img').getAttribute('src');
  await page.clock.runFor(15_100);
  await page.evaluate(() => window.dispatchEvent(new Event('release-old-decode')));
  const current = cards(page).nth(1);
  await expect(current).toHaveAttribute('aria-label', '洛杉矶 美国（北美洲）');
  await expect(current.locator('img')).not.toHaveAttribute('src', previous!);
});

test('long city names remain on one complete line throughout rotation', async ({ page }) => {
  await page.route('https://*.wikimedia.org/**', (route) => route.abort());
  await page.clock.install();
  await page.goto('/');
  await expect(clocks(page).locator('time').first()).not.toHaveText('—:—');
  for (let step = 0; step < 7; step += 1) {
    const before = await cards(page).last().getAttribute('aria-label');
    await page.clock.fastForward(15000);
    await expect(cards(page).last()).not.toHaveAttribute('aria-label', before!);
  }
  await expect(cards(page).last()).toHaveAttribute('aria-label', '布宜诺斯艾利斯 阿根廷（南美洲）');
  for (const width of [320, 768, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    for (const label of await clocks(page).locator('[data-clock-label]').all()) {
      expect(await label.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await expect(label).toHaveCSS('white-space', 'nowrap');
    }
  }
});
