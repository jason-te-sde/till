# Two stages, and the split is about what changes. The first resolves the dependency tree, which
# changes when a pom does; the second compiles, which changes on every commit. Copying the poms
# first means an ordinary code change re-uses the cached download layer instead of fetching the
# whole of Spring again.
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src

COPY pom.xml .
COPY till-core/pom.xml till-core/
COPY till-jdbc/pom.xml till-jdbc/
COPY till-testkit/pom.xml till-testkit/
COPY till-client/pom.xml till-client/
COPY till-server/pom.xml till-server/
# This project's own modules are excluded: they are not built yet, and asking Maven to resolve them
# from a repository would fail. Everything else is fetched here so the layer can be cached.
RUN mvn -B -ntp -q dependency:go-offline -DexcludeGroupIds=io.github.jason-te-sde

COPY till-core till-core
COPY till-jdbc till-jdbc
COPY till-testkit till-testkit
COPY till-client till-client
COPY till-server till-server
COPY till-web till-web

# The console's dependencies are not pre-warmed into a layer of their own. Doing it needs the
# frontend plugin's goals invoked by hand, which fails in ways that would have to be swallowed with
# `|| true` — and a build step whose failure is ignored is not a build step. `npm ci` takes twenty
# seconds; the honesty is worth more.
#
# `-Pweb`, so the image has the browser console in it: `docker compose up` should serve the whole
# thing rather than an API and a 404.
#
# Tests are not run here. They need a database and a browser, and an image build is the wrong place
# to discover that either is missing. CI runs every suite before it builds this.
RUN mvn -B -ntp -q -Pweb package -DskipTests

FROM eclipse-temurin:21-jre AS runtime

# curl is here for the health check and nothing else. The alternative is a Java class that exists
# only to make one HTTP request, which is more code in the shipped artifact than a 250 kB package.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Not root. The service writes nothing to the filesystem, so there is no reason for it to be able to.
RUN groupadd --system --gid 1000 till && useradd --system --uid 1000 --gid till till
USER till:till
WORKDIR /app

COPY --from=build /src/till-server/target/till-server-*.jar /app/till-server.jar
COPY --from=build /src/till-client/target/till-client-*-cli.jar /app/tillctl.jar

# A percentage rather than a fixed -Xmx, so the same image is right in a 256 MB container and in a
# 4 GB one. Nothing else is set: this has no interesting garbage collection story and inventing one
# would be a pile of flags nobody could justify later.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080 9101

# Readiness, not liveness. This asks whether the service can serve a request, which for something
# whose whole job is a database means asking the database. A liveness probe that did the same would
# restart the process every time the database hiccupped.
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=12 \
    CMD curl -fsS http://127.0.0.1:9101/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/till-server.jar"]
