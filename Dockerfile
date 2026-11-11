FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ ./src/
COPY --from=frontend /build/frontend/dist/ ./frontend/dist/
RUN mvn -B -q verify

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 gugupan && useradd --uid 10001 --gid gugupan --no-create-home gugupan \
    && mkdir -p /app/data && chown gugupan:gugupan /app/data && chmod 700 /app/data
WORKDIR /app
COPY --from=backend /build/target/gugupan.jar /app/gugupan.jar
USER 10001:10001
ENV GUGUPAN_DATA_DIR=/app/data
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx512m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gugupan.jar"]
