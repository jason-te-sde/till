# Two stages, and the split is about what changes. The first resolves the dependency tree, which
# changes when a pom does; the second compiles, which changes on every commit. Copying the poms
# first means an ordinary code change re-uses the cached download layer instead of fetching the
# whole of Spring again.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
COPY till-core/pom.xml till-core/
COPY till-jdbc/pom.xml till-jdbc/
COPY till-testkit/pom.xml till-testkit/
COPY till-client/pom.xml till-client/
COPY till-kafka/pom.xml till-kafka/
COPY till-server/pom.xml till-server/
COPY till-store/pom.xml till-store/
# This project's own modules are excluded: they are not built yet, and asking Maven to resolve them
# from a repository would fail. Everything else is fetched here so the layer can be cached.
RUN mvn -B -ntp -q dependency:go-offline -DexcludeGroupIds=io.github.jason-te-sde

COPY till-core till-core
COPY till-jdbc till-jdbc
COPY till-testkit till-testkit
COPY till-client till-client
COPY till-kafka till-kafka
COPY till-server till-server
COPY till-store till-store

# Tests are not run here. They need a database, a broker and Docker, and an image build is the wrong
# place to discover that any is missing. CI runs every suite before it builds this.
RUN mvn -B -ntp -q package -DskipTests

# The storefront. Its own stage because it shares nothing with the Java build but the repository, and
# the lock file is copied first for the same reason the poms are: an ordinary code change re-uses the
# cached `npm ci` layer.
FROM node:24-alpine AS web
WORKDIR /web
COPY till-web/package.json till-web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY till-web ./
# Typechecks as well as bundles: `build` is `tsc -b && vite build`, so a type error fails the image.
RUN npm run build

# The edge: the storefront's files, and the proxy in front of the store. nginx as an unprivileged
# user, so a compromised worker is not root in the container either.
FROM nginxinc/nginx-unprivileged:1.30-alpine AS edge
COPY docker/edge/default.conf /etc/nginx/conf.d/default.conf
COPY docker/edge/snippets /etc/nginx/snippets
COPY --from=web /web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
    CMD wget -qO- http://127.0.0.1:8080/healthz > /dev/null || exit 1

FROM eclipse-temurin:21-jre AS runtime

# curl is here for the health check and nothing else. The alternative is a Java class that exists
# only to make one HTTP request, which is more code in the shipped artifact than a 250 kB package.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Not root. The service writes nothing to the filesystem, so there is no reason for it to be able to.
#
# 10001 rather than 1000: the base image already ships a user at 1000, so pinning that fails the
# build outright. The id is pinned rather than left to the system because a Kubernetes
# `runAsUser` has to name a number, and high ids are out of the way of anything a distribution
# assigns.
RUN groupadd --system --gid 10001 till && useradd --system --uid 10001 --gid till till
USER till:till
WORKDIR /app

COPY --from=build /src/till-server/target/till-server-*.jar /app/till-server.jar
COPY --from=build /src/till-store/target/till-store-*.jar /app/till-store.jar
COPY --from=build /src/till-client/target/till-client-*-cli.jar /app/tillctl.jar

# 8080/9101 are the ledger, 8081/9102 the store. One image, two entrypoints: the two services
# share every dependency they have, and building two images to differ by one jar name would double
# the build time to save nothing.
EXPOSE 8080 8081 9101 9102

# Readiness, not liveness. This asks whether the service can serve a request, which for something
# whose whole job is a database means asking the database. A liveness probe that did the same would
# restart the process every time the database hiccupped.
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=12 \
    CMD curl -fsS http://127.0.0.1:9101/actuator/health/readiness || exit 1

# A percentage rather than a fixed -Xmx, so the same image is right in a 256 MB container and in a
# 4 GB one. Nothing else is set: this has no interesting garbage collection story, and inventing one
# would be a pile of flags nobody could justify later.
#
# On the entrypoint rather than in JAVA_TOOL_OPTIONS, because that variable applies to every JVM the
# image starts — including tillctl, which then prints "Picked up JAVA_TOOL_OPTIONS" to stderr before
# every single command it runs.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/till-server.jar"]
