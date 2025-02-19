/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.base.example.ext;

import com.swirlds.common.metrics.platform.DefaultMetricsProvider;
import com.swirlds.common.platform.NodeId;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.ClasspathFileConfigSource;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Static factory that creates {@link BaseContext}
 */
public class BaseContextFactory {
    private static final Logger logger = LogManager.getLogger(BaseContextFactory.class);

    private static final String APPLICATION_PROPERTIES = "app.properties";
    private static final Path EXTERNAL_PROPERTIES = Path.of("./config/app.properties");

    private BaseContextFactory() {}

    /**
     * @return an instance of {@link BaseContext} which holds
     * {@link Configuration} and {@link Metrics} for the rest of the application to use.
     */
    public static BaseContext create() {
        final Configuration configuration = getConfiguration();
        final Metrics metrics = getMetrics(configuration);
        return new BaseContext(metrics, configuration);
    }

    /**
     * @return a {@link Configuration} instance reading from classpath first or file second
     */
    private static Configuration getConfiguration() {
        try {

            final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                    .withSource(SystemEnvironmentConfigSource.getInstance())
                    .withSource(SystemPropertiesConfigSource.getInstance())
                    .withSource(new ClasspathFileConfigSource(Path.of(APPLICATION_PROPERTIES)))
                    .autoDiscoverExtensions();

            if (EXTERNAL_PROPERTIES.toFile().exists()) {
                configurationBuilder.withSources(new PropertyFileConfigSource(EXTERNAL_PROPERTIES));
            }

            return configurationBuilder.build();
        } catch (IOException e) {
            logger.error("Error reading configuration", e);
            throw new RuntimeException("Error reading configuration", e);
        }
    }

    /**
     * Creates and initiates the {@link Metrics} framework.
     *
     * @param configuration the configuration that holds the property to configure an instance of {@link Metrics}
     * @return the instance of {@link Metrics} to be used
     */
    @NonNull
    private static Metrics getMetrics(@NonNull final Configuration configuration) {
        final DefaultMetricsProvider metricsProvider = new DefaultMetricsProvider(configuration);
        final Metrics platformMetrics = metricsProvider.createPlatformMetrics(NodeId.FIRST_NODE_ID);
        // Starting the provider here has the effect that we lose support for csv file
        // We should start the provider AFTER all metrics are registered and our current patter doesn't allow for that
        metricsProvider.start();
        return platformMetrics;
    }
}
