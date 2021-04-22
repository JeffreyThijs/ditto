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
package org.eclipse.ditto.services.gateway.security.authentication.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.base.model.common.BinaryValidationResult;
import org.eclipse.ditto.model.jwt.Audience;
import org.eclipse.ditto.model.jwt.ImmutableJsonWebToken;
import org.eclipse.ditto.model.jwt.JsonWebToken;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.UnsupportedJwtException;

/**
 * Unit test for {@link DefaultJwtValidator}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class JwtValidatorTest {

    private static final JsonWebToken VALID_JSON_WEB_TOKEN =
            ImmutableJsonWebToken.fromToken(JwtTestConstants.VALID_JWT_TOKEN);

    private static final JsonWebToken VALID_JSON_WEB_TOKEN_WITHOUT_SIGNATURE =
            // Can't use ImmutableJsonWebToken as it already verifies that the token contains a signature
            new JsonWebTokenWithoutSignature(JwtTestConstants.UNSIGNED_JWT_TOKEN);

    private static final JsonWebToken INVALID_JSON_WEB_TOKEN =
            ImmutableJsonWebToken.fromToken(JwtTestConstants.EXPIRED_JWT_TOKEN);

    @Mock
    private PublicKeyProvider publicKeyProvider;

    @Test
    public void validate() throws ExecutionException, InterruptedException {
        when(publicKeyProvider.getPublicKey(JwtTestConstants.ISSUER, JwtTestConstants.KEY_ID)).thenReturn(
                CompletableFuture.completedFuture(Optional.of(JwtTestConstants.PUBLIC_KEY)));

        final JwtValidator underTest = DefaultJwtValidator.of(publicKeyProvider);

        final BinaryValidationResult jwtValidationResult = underTest.validate(VALID_JSON_WEB_TOKEN).get();

        assertThat(jwtValidationResult.isValid()).isTrue();
    }

    @Test
    public void validateFailsIfSignatureIsMissing() throws ExecutionException, InterruptedException {
        when(publicKeyProvider.getPublicKey(JwtTestConstants.ISSUER, JwtTestConstants.KEY_ID)).thenReturn(
                CompletableFuture.completedFuture(Optional.of(JwtTestConstants.PUBLIC_KEY)));

        final JwtValidator underTest = DefaultJwtValidator.of(publicKeyProvider);

        final BinaryValidationResult jwtValidationResult =
                underTest.validate(VALID_JSON_WEB_TOKEN_WITHOUT_SIGNATURE).get();

        assertThat(jwtValidationResult.isValid()).isFalse();
        assertThat(jwtValidationResult.getReasonForInvalidity()).isInstanceOf(UnsupportedJwtException.class);
    }

    @Test
    public void validateFails() throws ExecutionException, InterruptedException {
        when(publicKeyProvider.getPublicKey(JwtTestConstants.ISSUER, JwtTestConstants.KEY_ID))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(JwtTestConstants.PUBLIC_KEY)));

        final JwtValidator underTest = DefaultJwtValidator.of(publicKeyProvider);

        final BinaryValidationResult jwtValidationResult = underTest.validate(INVALID_JSON_WEB_TOKEN).get();

        assertThat(jwtValidationResult.isValid()).isFalse();
        assertThat(jwtValidationResult.getReasonForInvalidity()).isInstanceOf(ExpiredJwtException.class);
    }

    private static final class JsonWebTokenWithoutSignature implements JsonWebToken {

        private final String token;
        private final JsonObject header;
        private final JsonObject body;

        private JsonWebTokenWithoutSignature(final String token) {
            this.token = token;
            final String[] tokenParts = this.token.split("\\.");
            header = decodeJwtPart(tokenParts[0]);
            body = decodeJwtPart(tokenParts[1]);
        }

        private static JsonObject decodeJwtPart(final String jwtPart) {
            final Base64.Decoder decoder = Base64.getDecoder();
            return JsonFactory.newObject(new String(decoder.decode(jwtPart), StandardCharsets.UTF_8));
        }

        @Override
        public String getToken() {
            return token;
        }

        @Override
        public JsonObject getHeader() {
            return header;
        }

        @Override
        public JsonObject getBody() {
            return body;
        }

        @Override
        public String getKeyId() {
            return header.getValueOrThrow(JsonFields.KID);
        }

        @Override
        public String getIssuer() {
            return body.getValueOrThrow(JsonFields.ISS);
        }

        @Override
        public String getSignature() {
            return "";
        }

        @Override
        public List<String> getSubjects() {
            return List.of();
        }

        @Override
        public Audience getAudience() {
            return Audience.empty();
        }

        @Override
        public String getAuthorizedParty() {
            return "";
        }

        @Override
        public List<String> getScopes() {
            return List.of();
        }

        @Override
        public Instant getExpirationTime() {
            return Instant.ofEpochSecond(body.getValueOrThrow(JsonFields.EXP));
        }

        @Override
        public boolean isExpired() {
            return Instant.now().isAfter(getExpirationTime());
        }

    }

}
