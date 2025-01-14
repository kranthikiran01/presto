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
package io.prestosql.connector.system;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.connector.MockConnectorFactory;
import io.prestosql.spi.Plugin;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorFactory;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.testing.AbstractTestQueryFramework;
import io.prestosql.testing.DistributedQueryRunner;
import io.prestosql.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.google.common.collect.MoreCollectors.toOptional;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestSystemConnector
        extends AbstractTestQueryFramework
{
    private static final SchemaTableName SCHEMA_TABLE_NAME = new SchemaTableName("default", "test_table");
    private static final Function<SchemaTableName, List<ColumnMetadata>> DEFAULT_GET_COLUMNS = table -> ImmutableList.of(new ColumnMetadata("c", VARCHAR));
    private static final AtomicLong counter = new AtomicLong();

    private static Function<SchemaTableName, List<ColumnMetadata>> getColumns = DEFAULT_GET_COLUMNS;

    private final ExecutorService executor = Executors.newSingleThreadScheduledExecutor(threadsNamed(TestSystemConnector.class.getSimpleName()));

    protected TestSystemConnector()
    {
        super(TestSystemConnector::createQueryRunner);
    }

    public static QueryRunner createQueryRunner()
            throws Exception
    {
        Session defaultSession = testSessionBuilder()
                .setCatalog("mock")
                .setSchema("default")
                .build();

        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(defaultSession).build();
        queryRunner.installPlugin(new Plugin()
        {
            @Override
            public Iterable<ConnectorFactory> getConnectorFactories()
            {
                MockConnectorFactory connectorFactory = MockConnectorFactory.builder()
                        .withGetViews((session, schemaTablePrefix) -> ImmutableMap.of())
                        .withListTables((session, s) -> ImmutableList.of(SCHEMA_TABLE_NAME))
                        .withGetColumns(tableName -> getColumns.apply(tableName))
                        .build();
                return ImmutableList.of(connectorFactory);
            }
        });
        queryRunner.createCatalog("mock", "mock", ImmutableMap.of());
        return queryRunner;
    }

    @BeforeMethod
    public void cleanup()
    {
        getColumns = DEFAULT_GET_COLUMNS;
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
    }

    @Test
    public void testFinishedQueryIsCaptured()
    {
        String testQueryId = "test_query_id_" + counter.incrementAndGet();
        getQueryRunner().execute(format("EXPLAIN SELECT 1 AS %s FROM test_table", testQueryId));

        assertQuery(
                format("SELECT state FROM system.runtime.queries WHERE query LIKE '%%%s%%' AND query NOT LIKE '%%system.runtime.queries%%'", testQueryId),
                "VALUES 'FINISHED'");
    }

    @Test(timeOut = 60_000)
    public void testQueryDuringAnalysisIsCaptured()
    {
        SettableFuture<List<ColumnMetadata>> metadataFuture = SettableFuture.create();
        getColumns = schemaTableName -> {
            try {
                return metadataFuture.get();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        String testQueryId = "test_query_id_" + counter.incrementAndGet();
        Future<?> queryFuture = executor.submit(() -> {
            getQueryRunner().execute(format("EXPLAIN SELECT 1 AS %s FROM test_table", testQueryId));
        });

        assertQueryEventually(
                getSession(),
                format("SELECT state FROM system.runtime.queries WHERE query LIKE '%%%s%%' AND query NOT LIKE '%%system.runtime.queries%%'", testQueryId),
                "VALUES 'WAITING_FOR_RESOURCES'",
                new Duration(10, SECONDS));
        assertFalse(metadataFuture.isDone());
        assertFalse(queryFuture.isDone());

        metadataFuture.set(ImmutableList.of(new ColumnMetadata("a", BIGINT)));

        assertQueryEventually(
                getSession(),
                format("SELECT state FROM system.runtime.queries WHERE query LIKE '%%%s%%' AND query NOT LIKE '%%system.runtime.queries%%'", testQueryId),
                "VALUES 'FINISHED'",
                new Duration(10, SECONDS));
        assertTrue(queryFuture.isDone());
    }

    @Test(timeOut = 60_000)
    public void testQueryKillingDuringAnalysis()
            throws InterruptedException
    {
        SettableFuture<List<ColumnMetadata>> metadataFuture = SettableFuture.create();
        getColumns = schemaTableName -> {
            try {
                return metadataFuture.get();
            }
            catch (InterruptedException e) {
                metadataFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        };
        String testQueryId = "test_query_id_" + counter.incrementAndGet();
        Future<?> queryFuture = executor.submit(() -> {
            getQueryRunner().execute(format("EXPLAIN SELECT 1 AS %s FROM test_table", testQueryId));
        });

        Thread.sleep(100);

        Optional<Object> queryId = computeActual(format("SELECT query_id FROM system.runtime.queries WHERE query LIKE '%%%s%%' AND query NOT LIKE '%%system.runtime.queries%%'", testQueryId))
                .getOnlyColumn()
                .collect(toOptional());
        assertFalse(metadataFuture.isDone());
        assertFalse(queryFuture.isDone());
        assertTrue(queryId.isPresent());

        getQueryRunner().execute(format("CALL system.runtime.kill_query('%s', 'because')", queryId.get()));

        Thread.sleep(100);
        assertTrue(queryFuture.isDone());
        assertTrue(metadataFuture.isCancelled());
    }
}
