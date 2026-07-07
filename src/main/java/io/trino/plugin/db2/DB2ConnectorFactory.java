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

import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.opentelemetry.api.OpenTelemetry;
import io.trino.plugin.jdbc.ExtraCredentialsBasedIdentityCacheMappingModule;
import io.trino.plugin.jdbc.JdbcConnector;
import io.trino.plugin.jdbc.JdbcModule;
import io.trino.plugin.jdbc.credential.CredentialProviderModule;
import io.trino.spi.Node;
import io.trino.spi.VersionEmbedder;
import io.trino.spi.catalog.CatalogName;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.spi.type.TypeManager;

import java.util.Map;

import static io.airlift.configuration.ConfigurationAwareModule.combine;
import static java.util.Objects.requireNonNull;

/**
 * Same wiring as {@code io.trino.plugin.jdbc.JdbcConnectorFactory}, but without
 * {@code Versions.checkStrictSpiVersionMatch}. That strict check is meant only for plugins
 * distributed with the Trino server itself. This connector is distributed independently and
 * must also run on vendor builds of Trino that report a suffixed SPI version (for example
 * Stackable's {@code 477-stackable25.11.0}) while remaining binary compatible with the
 * SPI version it was compiled against.
 */
public class DB2ConnectorFactory
        implements ConnectorFactory
{
    @Override
    public String getName()
    {
        return "db2";
    }

    @Override
    public Connector create(String catalogName, Map<String, String> requiredConfig, ConnectorContext context)
    {
        requireNonNull(requiredConfig, "requiredConfig is null");

        Bootstrap app = new Bootstrap(
                binder -> binder.bind(TypeManager.class).toInstance(context.getTypeManager()),
                binder -> binder.bind(Node.class).toInstance(context.getCurrentNode()),
                binder -> binder.bind(VersionEmbedder.class).toInstance(context.getVersionEmbedder()),
                binder -> binder.bind(OpenTelemetry.class).toInstance(context.getOpenTelemetry()),
                binder -> binder.bind(CatalogName.class).toInstance(new CatalogName(catalogName)),
                new JdbcModule(),
                // same additional modules that JdbcPlugin combines with the connector module
                combine(
                        new CredentialProviderModule(),
                        new ExtraCredentialsBasedIdentityCacheMappingModule(),
                        new DB2ClientModule()));

        Injector injector = app
                .doNotInitializeLogging()
                .setRequiredConfigurationProperties(requiredConfig)
                .initialize();

        return injector.getInstance(JdbcConnector.class);
    }
}
