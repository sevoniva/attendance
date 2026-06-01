# 阶段1：构建前端
ARG HTTP_PROXY
ARG HTTPS_PROXY
ENV HTTP_PROXY=${HTTP_PROXY}
ENV HTTPS_PROXY=${HTTPS_PROXY}

FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# 阶段2：构建后端
FROM maven:3.9-eclipse-temurin-21-alpine AS backend-builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
# 将前端构建产物复制到后端静态资源目录
COPY --from=frontend-builder /app/src/main/resources/static/app ./src/main/resources/static/app
RUN mvn package -DskipTests -q

# 阶段3：运行
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-builder /app/target/attendance-audit-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
