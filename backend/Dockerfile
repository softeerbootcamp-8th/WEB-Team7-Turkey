# 로컬 부하 테스트에서 spring 앱도 docker-compose로 띄우기 위한 이미지.
# 실제 배포(EC2)는 이 이미지를 쓰지 않는다 — 배포는 여전히 jar를 systemd로 직접 실행한다
# (deploy.yml, docs/05-local-dev.md 참고). 이 Dockerfile은 로컬 전용이다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
# bootJar만 실행하면 plain jar(-plain)가 만들어지지 않아 산출물이 하나로 고정된다.
RUN chmod +x gradlew && ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
# JAVA_HEAP_OPTS 기본값 -Xmx228m: 프로덕션 t4g.micro의 ergonomic 기본값(총 메모리 906MiB의
# 25%, #259 실측)과 동일하게 고정 — 로컬 --scale 부하테스트가 프로덕션 힙 제약을 근사하게
# 재현하도록 한다. JAVA_HEAP_OPTS/JAVA_GC_OPTS는 힙·GC 튜닝 실험(#502)에서 이미지를 다시
# 빌드하지 않고 컨테이너 환경변수만 바꿔 비교하기 위해 뺐다 — 안 주면 이 기본값 그대로다.
# JAVA_GC_ALGO 기본값 -XX:+UseG1GC: GC 알고리즘 자체를 바꿀 때 쓴다(SerialGC 비교 등, #502).
# JAVA_GC_OPTS는 그 알고리즘 안에서의 튜닝 옵션(IHOP 등)이나 진단 플래그용 — 알고리즘과
# 분리해 두 조합을 각자 안 건드리고 바꿀 수 있게 한다. 안 주면 예전과 동일하게 G1 그대로다.
# 파일명에 hostname을 넣는 건 --scale app=N으로 띄운 컨테이너들이 같은 /dumps 볼륨을
# 공유하기 때문 — 없으면 전부 PID 1이라 덤프·GC 로그가 서로 덮어써진다. %p(PID)도 같이
# 넣는 건 같은 컨테이너가 재시작돼도(고정 파일명이면 JVM이 기존 덤프를 안 덮어써 두 번째
# OOM부터 덤프가 안 남는다) 매번 새 파일이 생기게 하기 위함이다(#374).
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_GC_ALGO:--XX:+UseG1GC} ${JAVA_HEAP_OPTS:--Xmx228m} ${JAVA_GC_OPTS:-} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/heapdump-$(hostname)-%p.hprof -Xlog:gc*,gc+heap=debug:file=/dumps/gc-$(hostname).log:time,uptime,level,tags:filecount=5,filesize=20m -jar app.jar"]
