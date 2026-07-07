 # Development

 Developers should follow the [development guidelines](https://github.com/trinodb/trino/blob/81e9233eae31f2f3b425aa63a9daee8a00bc8344/DEVELOPMENT.md)
 from the Trino community.

## Build

Requires a Temurin or Oracle JDK matching the Java version of the Trino release
(Java 24 for Trino 478); other JDK vendors are rejected by the Maven enforcer.

    mvn clean install

## Release
First update the `main` branch of this repo via PR process. Then, go to https://github.com/IBM/trino-db2/releases to draft your release. Configure the release to create a new branch named after the Trino version (e.g. 372). Before publishing the release, build the plugin locally with `mvn clean install`, and upload the resulting archive `target/trino-db2-[version].zip` to the release binaries. Then, you may click "publish release".

## Build a container image including this connector

It uses multi-stage build and the trinodb container image from community as the
base image.

    docker build -t "<name>/<tag>" --build-arg BASE="trinodb/trino:<trino_verson_from_pom>" .

## Testing

### Integration tests with Testcontainers

`TestDB2IntegrationSmokeTest` starts a disposable Db2 server in a Docker
container (via [Testcontainers](https://www.testcontainers.org/modules/databases/db2/)),
spins up a Trino cluster (`DistributedQueryRunner`) with a `db2` catalog
pointing at that server, copies the TPC-H `nation` and `region` tables through
the connector's write path, and verifies reads, writes, DDL, and type mappings.

Requirements:

* a running Docker daemon
* ~4 GB free disk space (the Db2 image is downloaded on first run)
* running the test accepts the license of the Db2 Community Edition image
  (the container is started with `LICENSE=accept`)
* the Db2 images are amd64-only; on Apple Silicon, Docker Desktop runs them
  via Rosetta emulation, which increases startup time to several minutes

Run all tests (unit + integration):

    mvn test

Run only the integration test:

    mvn test -Dtest=TestDB2IntegrationSmokeTest

Run only the unit tests (no Docker needed):

    mvn test -Dtest='TestDB2Config,TestDB2Plugin'

### Testing against different Db2 versions

The Db2 version is selected with the system property `db2.image`
(default: `icr.io/db2_community/db2:11.5.9.0`):

    mvn test -Dtest=TestDB2IntegrationSmokeTest -Ddb2.image=icr.io/db2_community/db2:12.1.2.0

Available image tags (e.g. `11.5.8.0`, `11.5.9.0`, `12.1.0.0`, `12.1.2.0`) are
listed in the [IBM Container Registry](https://www.ibm.com/docs/en/db2/12.1?topic=system-linux).
The GitHub Actions workflow runs the test suite against a matrix of Db2
versions on every push.

### End-to-end test with the Stackable Trino image

[stackable-test/](stackable-test/) contains a Docker Compose setup that runs the
locally built connector inside the [Stackable](https://stackable.tech/) Trino
image (`oci.stackable.tech/sdp/trino:477-stackable25.11.0`) together with a Db2
container — mirroring a Stackable Data Platform deployment:

    mvn clean install -DskipTests        # builds target/trino-db2-477/
    cd stackable-test
    docker compose up -d                 # Db2 needs a few minutes to initialize
    ./run-smoke-test.sh                  # queries through the Trino REST API
    docker compose down -v               # tear down

The connector version must match the Trino version of the image exactly —
check available image tags with the Stackable release notes (SDP 25.11 ships
Trino 477). The smoke test exercises catalog listing, schema/table creation,
INSERT, SELECT, and CTAS through the `db2` catalog.

Vendor builds like Stackable report a suffixed SPI version (e.g.
`477-stackable25.11.0`). This connector therefore uses its own
`DB2ConnectorFactory` without Trino's `checkStrictSpiVersionMatch` (which is
reserved for plugins distributed with the Trino server itself) — it still must
be compiled against the same base Trino version as the server.

### Deploying on Kubernetes (Stackable operator)

[stackable-test/k8s/](stackable-test/k8s/) contains example manifests for a
Stackable Data Platform deployment:

* `trino-cluster.yaml` — `TrinoCluster` with the connector provided through
  `podOverrides` (initContainer + emptyDir) on both roles; for production,
  build a custom image instead (see comments in the file)
* `trino-catalog-db2.yaml` — the `db2` catalog as a `TrinoCatalog` resource
  using the `generic` connector, credentials from a Secret

**Note on Trino 477 artifacts**: Trino 477 was only partially published to
Maven Central (`trino-spi` exists, but `trino-base-jdbc`, `trino-main`,
`trino-testing` etc. are missing). To build this connector against 477, install
the modules locally first (one-time, ~5 min):

    git clone --depth 1 --branch 477 --filter=blob:none https://github.com/trinodb/trino.git
    cd trino
    ./mvnw install -pl plugin/trino-base-jdbc,plugin/trino-tpch,testing/trino-testing -am \
        -DskipTests -Dair.check.skip-all=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true

### Manual testing

To manually test against an actual Db2 database after coding a feature in
Java code, I'd recommend following this process to iterate:

1. Clone this repo to a local development environment, e.g., IntelliJ IDEA. And
keep code changes in a branch.
1. Run `mvn clean install` or the Maven tool window of the IDE to build this
connector, while addressing errors/problems from build output.
1. Config a separate trinodb server with the built connector by creating a file
named `docker-compose.yml`:
    ```YAML
    # docker-compose.yml
    version: "3.7"

    services:
      trino-coordinator:
        image: trinodb/trino:<trino_verson_from_pom>
        container_name: trino-coordinator
        volumes:
        - source: ./target/trino-db2-<trino_verson_from_pom>
          target: /usr/lib/trino/plugin/db2
          type: bind
        - source: ./conf/trino
          target: /etc/trino
          type: bind
        ports:
          - "8080:8080"
    ```
1. Make sure creating a connector config under `./conf/trino/catalog` to connect
to an actual Db2 database. see details from [Connection Configuration](README.md#connection-configuration).
1. Start this local trinodb server by running `docker-compose up -d`
1. Connect to this local trinodb server via CLI to perform queries while
capturing server output from container logs by running command `docker logs trino-coordinator`.
1. If changing Java code, delete this local trinodb server by running command
`docker-compose down` then start from step 2.
