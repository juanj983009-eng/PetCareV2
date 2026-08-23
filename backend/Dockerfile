FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package \
    && cp target/petcare-enterprise-0.0.1-SNAPSHOT.jar target/application.jar

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 petcare \
    && useradd --system --uid 10001 --gid petcare --home-dir /app --shell /usr/sbin/nologin petcare

WORKDIR /app

COPY --from=build --chown=petcare:petcare /workspace/target/application.jar app.jar

USER petcare

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
