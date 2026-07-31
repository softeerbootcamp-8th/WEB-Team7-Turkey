import { defineConfig } from 'orval'

/**
 * 백엔드 OpenAPI 스펙으로부터 API 클라이언트와 React Query 훅을 생성한다.
 * 출력물은 src/api/generated 이며 수정 금지(자동 생성).
 * 실제 스펙 경로(URL 또는 파일)는 백엔드 확정 후 input.target 에 지정한다.
 */
export default defineConfig({
  turkey: {
    input: {
      // springdoc 이 노출하는 실시간 스펙. 생성 전에 백엔드를 local 프로파일로 띄워야 한다.
      //   cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
      target: 'http://localhost:8080/v3/api-docs',
    },
    output: {
      mode: 'tags-split',
      target: './src/api/generated',
      client: 'react-query',
      httpClient: 'axios',
      override: {
        mutator: {
          path: './src/lib/axios.ts',
          name: 'customInstance',
        },
        // 에러 타입을 AxiosError 로 지정 → 훅의 error 가 타입 안전해진다.
        // (path 의 export 별칭 ErrorType<E> 를 사용)
        query: {
          useQuery: true,
        },
      },
    },
  },
})
