module.exports = {
  root: true,
  extends: ['next/core-web-vitals'],
  settings: { next: { rootDir: require('path').join(__dirname, 'apps/web/') } },
  ignorePatterns: ['node_modules/', '.next/', 'work/', 'playwright-report/', 'test-results/'],
};
