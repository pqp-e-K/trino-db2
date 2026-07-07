/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.db2;

import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * A disposable Db2 server running in a Docker container, managed by Testcontainers.
 * <p>
 * The Db2 version can be selected with the system property {@code db2.image}, e.g.
 * {@code -Ddb2.image=icr.io/db2_community/db2:12.1.0.0}. Any image that behaves like
 * the official Db2 Community Edition image is supported.
 * <p>
 * Starting this server accepts the IBM Db2 Community Edition license
 * (the container is started with {@code LICENSE=accept}).
 */
public class TestingDB2Server
        implements Closeable
{
    public static final String IMAGE_PROPERTY = "db2.image";
    public static final String DEFAULT_IMAGE = "icr.io/db2_community/db2:11.5.9.0";

    // Db2 needs several minutes to initialize the instance and database on first start,
    // considerably more when the amd64-only image runs emulated (e.g. on Apple Silicon)
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(20);

    private final Db2Container container;

    public TestingDB2Server()
    {
        this(System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE));
    }

    public TestingDB2Server(String dockerImageName)
    {
        container = new Db2Container(DockerImageName.parse(dockerImageName).asCompatibleSubstituteFor("ibmcom/db2"))
                .acceptLicense()
                .withStartupTimeoutSeconds((int) STARTUP_TIMEOUT.toSeconds())
                .waitingFor(new LogMessageWaitStrategy()
                        .withRegEx(".*Setup has completed\\..*")
                        .withStartupTimeout(STARTUP_TIMEOUT));
        container.start();
    }

    public void execute(String sql)
    {
        try (Connection connection = DriverManager.getConnection(getJdbcUrl(), getUser(), getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to execute statement: " + sql, e);
        }
    }

    public String getJdbcUrl()
    {
        return container.getJdbcUrl();
    }

    public String getUser()
    {
        return container.getUsername();
    }

    public String getPassword()
    {
        return container.getPassword();
    }

    @Override
    public void close()
    {
        container.stop();
    }
}
