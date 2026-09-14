# Multi-stage Dockerfile para videoclub-agent (Java 25 + Spring Boot 4.1.1)
#
# Tres etapas con responsabilidades distintas:
#  - dev     : solo dependencias de Maven. A proposito no copia src/ ni define
#              CMD: el codigo y el comando se inyectan desde docker-compose.yml
#              (bind mount + command: mvn spring-boot:run).
#  - build   : hereda de dev y compila el .jar. Con `FROM dev AS build` se
#              reutiliza la capa de dependencias en cache.
#  - runtime : imagen productiva (Alpine JRE 25, usuario no-root, HEALTHCHECK).

# ---- Etapa dev (docker-compose, hot-reload) ----
FROM maven:3.9-eclipse-temurin-25 AS dev
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline

# ---- Etapa build ----
FROM dev AS build
COPY src ./src
RUN mvn clean package -DskipTests

# ---- Etapa runtime ----
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

LABEL org.opencontainers.image.title="videoclub-agent" \
      org.opencontainers.image.description="AI Agent with Spring AI and MCP tools for VideoClub" \
      org.opencontainers.image.authors="VideoClub UNRN"

RUN apk add --no-cache curl
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app

ENV PORT=8085
EXPOSE ${PORT}

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=30s \
    CMD curl -f http://localhost:${PORT}/api/agent/health || exit 1

CMD ["java", "-jar", "app.jar"]
