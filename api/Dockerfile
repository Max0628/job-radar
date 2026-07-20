FROM docker.io/library/eclipse-temurin:21-jre-alpine AS builder
WORKDIR /workspace
COPY api/build/libs/api-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

FROM docker.io/library/eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=builder /workspace/app/dependencies/ ./
COPY --from=builder /workspace/app/spring-boot-loader/ ./
COPY --from=builder /workspace/app/snapshot-dependencies/ ./
COPY --from=builder /workspace/app/application/ ./
USER spring:spring
EXPOSE 8083
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
