/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.security.exception;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A {@link RuntimeException} that is thrown in the case where a user does not
 * have sufficient authorization to execute operation on platform.
 */
public class NoAuthorizationException extends RuntimeException {

    private static final List<String> BLANKET_PERMISSIONS = Arrays.asList("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ");

    private final List<String> requiredPermissions;

    public NoAuthorizationException(final String message) {
        this(message, Collections.<String> emptyList());
    }

    public NoAuthorizationException(final String message, final String... requiredPermissions) {
        this(message, Arrays.asList(requiredPermissions));
    }

    public NoAuthorizationException(final String message, final List<String> requiredPermissions) {
        super(message);
        this.requiredPermissions = withoutBlanketPermissions(requiredPermissions);
    }

    private static List<String> withoutBlanketPermissions(final List<String> permissions) {
        if (permissions == null) { return Collections.<String> emptyList(); }

        final List<String> specificPermissions = new ArrayList<>();
        for (final String permission : permissions) {
            if (!BLANKET_PERMISSIONS.contains(permission)) {
                specificPermissions.add(permission);
            }
        }
        return specificPermissions;
    }

    public List<String> getRequiredPermissions() {
        return this.requiredPermissions;
    }
}