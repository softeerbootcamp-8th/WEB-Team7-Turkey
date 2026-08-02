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
ENTRYPOINT ["java", "-jar", "app.jar"]
