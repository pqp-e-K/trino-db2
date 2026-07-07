ARG BASE
FROM docker.io/library/maven:3.9-eclipse-temurin-24 AS builder
ENV MAVEN_FAST_INSTALL="-DskipTests -Dair.check.skip-all=true -Dmaven.javadoc.skip=true -B -q -T C1"
# Trino 477 was only partially published to Maven Central, so the required
# modules have to be built from source first
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git clone --depth 1 --branch 477 --filter=blob:none https://github.com/trinodb/trino.git /root/trino \
    && cd /root/trino \
    && ./mvnw install $MAVEN_FAST_INSTALL -pl plugin/trino-base-jdbc,plugin/trino-tpch,testing/trino-testing -am -Dmaven.source.skip=true \
    && rm -rf /root/trino
WORKDIR /root/trino-db2
COPY . /root/trino-db2
RUN mvn install $MAVEN_FAST_INSTALL

FROM $BASE
COPY --from=builder --chown=trino:trino /root/trino-db2/target/trino-db2-*/* /usr/lib/trino/plugin/db2/
