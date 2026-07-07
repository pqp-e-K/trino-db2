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
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import static io.trino.tpch.TpchTable.NATION;
import static io.trino.tpch.TpchTable.REGION;

/**
 * Integration test running queries through a Trino cluster ({@code DistributedQueryRunner})
 * with the Db2 connector against a real Db2 server in a Docker container.
 * <p>
 * Requires a running Docker daemon. Select the Db2 version with
 * {@code -Ddb2.image=<image>}, see {@link TestingDB2Server}.
 */
public class TestDB2IntegrationSmokeTest
        extends AbstractTestQueryFramework
{
    private TestingDB2Server server;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = closeAfterClass(new TestingDB2Server());
        return DB2QueryRunner.builder(server)
                .setInitialTables(ImmutableList.of(NATION, REGION))
                .build();
    }

    @Test
    public void testShowSchemas()
    {
        assertQuery("SHOW SCHEMAS LIKE 'tpch'", "VALUES 'tpch'");
    }

    @Test
    public void testListTables()
    {
        assertQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'tpch' AND table_name IN ('nation', 'region')",
                "VALUES 'nation', 'region'");
    }

    @Test
    public void testDescribeTable()
    {
        assertQuery(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'tpch' AND table_name = 'region'",
                "VALUES ('regionkey', 'bigint'), ('name', 'varchar(25)'), ('comment', 'varchar(152)')");
    }

    @Test
    public void testSelect()
    {
        assertQuery("SELECT count(*) FROM nation");
        assertQuery("SELECT name FROM nation WHERE nationkey = 3");
    }

    @Test
    public void testPredicatePushdown()
    {
        assertQuery("SELECT name FROM nation WHERE regionkey = 1");
        assertQuery("SELECT name FROM nation WHERE name LIKE 'A%'");
    }

    @Test
    public void testAggregation()
    {
        assertQuery("SELECT regionkey, count(*) FROM nation GROUP BY regionkey");
    }

    @Test
    public void testJoin()
    {
        assertQuery("SELECT n.name FROM nation n JOIN region r ON n.regionkey = r.regionkey WHERE r.name = 'EUROPE'");
    }

    @Test
    public void testCreateInsertSelectDrop()
    {
        String tableName = "test_create_insert";
        assertUpdate("CREATE TABLE " + tableName + " (id bigint, name varchar(50))");
        try {
            assertUpdate("INSERT INTO " + tableName + " VALUES (1, 'one'), (2, 'two')", 2);
            assertQuery("SELECT name FROM " + tableName + " WHERE id = 2", "VALUES 'two'");
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
        assertQueryReturnsEmptyResult("SHOW TABLES LIKE '" + tableName + "'");
    }

    @Test
    public void testCreateTableAsSelect()
    {
        String tableName = "test_ctas";
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT nationkey, name FROM nation", 25);
        try {
            assertQuery("SELECT count(*) FROM " + tableName, "SELECT 25");
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    @Test
    public void testUnboundedVarchar()
    {
        String tableName = "test_unbounded_varchar";
        assertUpdate("CREATE TABLE " + tableName + " (v varchar)");
        try {
            assertUpdate("INSERT INTO " + tableName + " VALUES ('unbounded value')", 1);
            assertQuery("SELECT v FROM " + tableName, "VALUES 'unbounded value'");
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    @Test
    public void testTimestampWriteRoundTrip()
    {
        String tableName = "test_ts_write";
        assertUpdate("CREATE TABLE " + tableName + " (ts timestamp(6), ts9 timestamp(9))");
        try {
            assertUpdate("INSERT INTO " + tableName + " VALUES (TIMESTAMP '2020-01-15 12:34:56.123456', TIMESTAMP '2020-01-15 12:34:56.123456789')", 1);
            // sub-millisecond precision must survive the write path
            assertQuery(
                    "SELECT CAST(ts AS varchar), CAST(ts9 AS varchar) FROM " + tableName,
                    "VALUES ('2020-01-15 12:34:56.123456', '2020-01-15 12:34:56.123456789')");
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    @Test
    public void testDb2NativeTypes()
    {
        String tableName = "tpch.test_native_types";
        server.execute("CREATE TABLE " + tableName + " (" +
                "c_smallint SMALLINT, " +
                "c_integer INTEGER, " +
                "c_bigint BIGINT, " +
                "c_double DOUBLE, " +
                "c_decimal DECIMAL(10,2), " +
                "c_varchar VARCHAR(30), " +
                "c_date DATE, " +
                "c_timestamp TIMESTAMP, " +
                "c_timestamp9 TIMESTAMP(9))");
        try {
            server.execute("INSERT INTO " + tableName + " VALUES (" +
                    "32, " +
                    "42, " +
                    "123456789012, " +
                    "1.25, " +
                    "1234.56, " +
                    "'hello', " +
                    "DATE '2020-01-15', " +
                    "TIMESTAMP '2020-01-15 12:34:56.123456', " +
                    "TIMESTAMP '2020-01-15 12:34:56.123456789')");

            assertQuery(
                    "SELECT c_smallint, c_integer, c_bigint, c_double, c_decimal, c_varchar, c_date FROM test_native_types",
                    "VALUES (32, 42, 123456789012, 1.25, 1234.56, 'hello', DATE '2020-01-15')");
            // Db2 TIMESTAMP has precision 6 by default; TIMESTAMP(9) exercises the long timestamp read path
            assertQuery(
                    "SELECT CAST(c_timestamp AS varchar) FROM test_native_types",
                    "VALUES '2020-01-15 12:34:56.123456'");
            assertQuery(
                    "SELECT CAST(c_timestamp9 AS varchar) FROM test_native_types",
                    "VALUES '2020-01-15 12:34:56.123456789'");
        }
        finally {
            server.execute("DROP TABLE " + tableName);
        }
    }
}
