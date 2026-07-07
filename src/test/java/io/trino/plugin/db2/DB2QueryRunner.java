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

import com.google.common.collect.ImmutableList;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.DistributedQueryRunner;
import io.trino.tpch.TpchTable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.QueryAssertions.copyTpchTables;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.util.Objects.requireNonNull;

public final class DB2QueryRunner
{
    private DB2QueryRunner() {}

    public static final String CATALOG = "db2";
    private static final String TEST_SCHEMA = "tpch";

    public static Builder builder(TestingDB2Server server)
    {
        return new Builder(server)
                .addConnectorProperties(Map.of(
                        "connection-url", server.getJdbcUrl(),
                        "connection-user", server.getUser(),
                        "connection-password", server.getPassword()));
    }

    public static final class Builder
            extends DistributedQueryRunner.Builder<Builder>
    {
        private final TestingDB2Server server;
        private final Map<String, String> connectorProperties = new HashMap<>();
        private List<TpchTable<?>> initialTables = ImmutableList.of();

        private Builder(TestingDB2Server server)
        {
            super(testSessionBuilder()
                    .setCatalog(CATALOG)
                    .setSchema(TEST_SCHEMA)
                    .build());
            this.server = requireNonNull(server, "server is null");
        }

        public Builder addConnectorProperties(Map<String, String> connectorProperties)
        {
            this.connectorProperties.putAll(requireNonNull(connectorProperties, "connectorProperties is null"));
            return this;
        }

        public Builder setInitialTables(Iterable<TpchTable<?>> initialTables)
        {
            this.initialTables = ImmutableList.copyOf(requireNonNull(initialTables, "initialTables is null"));
            return this;
        }

        @Override
        public DistributedQueryRunner build()
                throws Exception
        {
            DistributedQueryRunner queryRunner = super.build();
            try {
                queryRunner.installPlugin(new TpchPlugin());
                queryRunner.createCatalog("tpch", "tpch");

                queryRunner.installPlugin(new DB2Plugin());
                queryRunner.createCatalog(CATALOG, "db2", connectorProperties);

                server.execute("CREATE SCHEMA " + TEST_SCHEMA);
                copyTpchTables(queryRunner, "tpch", TINY_SCHEMA_NAME, initialTables);

                return queryRunner;
            }
            catch (Throwable e) {
                closeAllSuppress(e, queryRunner);
                throw e;
            }
        }
    }
}
