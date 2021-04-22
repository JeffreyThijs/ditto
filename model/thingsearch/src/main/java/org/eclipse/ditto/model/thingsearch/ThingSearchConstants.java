/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.model.thingsearch;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.entity.type.EntityType;

/**
 * Constants to support working with Thing search.
 *
 * @since 1.1.0
 */
@Immutable
public final class ThingSearchConstants {

    private ThingSearchConstants() {
        throw new AssertionError();
    }

    /**
     * The entity type of Thing search.
     */
    public static final EntityType ENTITY_TYPE = EntityType.of("thing-search");

}
