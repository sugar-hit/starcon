/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  transpilePackages: ['@starcon/ui'],
  experimental: {
    // 启用 typedRoutes 增加类型安全的路由
  },
  output: 'standalone', // Dockerfile.web 用
};

module.exports = nextConfig;
