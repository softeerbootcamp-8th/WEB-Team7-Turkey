import { defineConfig, searchForWorkspaceRoot } from 'vite'
import react from '@vitejs/plugin-react'
import { TanStackRouterVite } from '@tanstack/router-plugin/vite'
import path from 'node:path'

export default defineConfig({
  plugins: [
    // routeTree.gen.ts 를 src/routes 구조로부터 자동 생성한다.
    TanStackRouterVite({ target: 'react', autoCodeSplitting: true }),
    react(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    fs: {
      // local 프로파일의 배송 인증 사진 저장 위치. /@fs URL은 이 경로 안에서만 허용한다.
      allow: [
        searchForWorkspaceRoot(process.cwd()),
        path.resolve(__dirname, '../data'),
        path.resolve(__dirname, '../../data'),
      ],
    },
    // 개발 중 API·SSE 는 EC2 Spring Boot(별도 Origin)로 프록시. 배포 시엔 CloudFront/S3 분리.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
