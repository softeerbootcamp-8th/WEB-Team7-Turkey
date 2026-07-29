import { defineConfig } from 'vitest/config'
import path from 'node:path'

/**
 * 테스트 전용 설정.
 *
 * vite.config.ts 를 그대로 쓰지 않는 이유: TanStackRouterVite 플러그인이 테스트 실행 중에
 * routeTree.gen.ts 를 다시 써서 자동 생성물이 테스트 부수효과로 바뀐다.
 * 테스트가 필요한 것은 경로 별칭뿐이다.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
})
