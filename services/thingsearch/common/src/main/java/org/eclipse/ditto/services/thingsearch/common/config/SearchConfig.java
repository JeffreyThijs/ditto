/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.thingsearch.common.config;

import java.util.Optional;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.service.config.ServiceSpecificConfig;
import org.eclipse.ditto.services.utils.config.KnownConfigValue;
import org.eclipse.ditto.services.utils.health.config.WithHealthCheckConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.WithIndexInitializationConfig;
import org.eclipse.ditto.services.utils.persistence.mongo.config.WithMongoDbConfig;
import org.eclipse.ditto.services.utils.persistence.operations.WithPersistenceOperationsConfig;

/**
 * Provides the configuration settings of the Search service.
 */
@Immutable
public interface SearchConfig extends ServiceSpecificConfig, WithHealthCheckConfig, WithPersistenceOperationsConfig,
        WithMongoDbConfig, WithIndexInitializationConfig {

    Optional<String> getMongoHintsByNamespace();

    /**
     * Returns the {@code QueryCriteriaValidator} to be used for validating and decoding
     * {@link org.eclipse.ditto.model.query.criteria.Criteria} of a
     * {@link org.eclipse.ditto.signals.commands.thingsearch.query.ThingSearchQueryCommand}.
     *
     * @return the config.
     */
    String getQueryValidatorImplementation();

    /**
     * Returns the configuration settings for the search updating functionality.
     *
     * @return the config.
     */
    UpdaterConfig getUpdaterConfig();

    /**
     * An enumeration of the known config path expressions and their associated default values for SearchConfig.
     */
    enum SearchConfigValue implements KnownConfigValue {

        /**
         * Default value is {@code null}.
         */
        MONGO_HINTS_BY_NAMESPACE("mongo-hints-by-namespace", null),

        /**
         * The {@code QueryCriteriaValidator} used for decoding and validating {@link org.eclipse.ditto.model.query.criteria.Criteria}
         * of a {@link org.eclipse.ditto.signals.commands.thingsearch.query.ThingSearchQueryCommand}.
         *
         * @since 1.6.0
         */
        QUERY_CRITERIA_VALIDATOR("query-criteria-validator.implementation",
                "org.eclipse.ditto.services.thingsearch.persistence.query.validation.DefaultQueryCriteriaValidator");

        private final String path;
        private final Object defaultValue;

        private SearchConfigValue(final String path, final Object defaultValue) {
            this.path = path;
            this.defaultValue = defaultValue;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

    }

}
