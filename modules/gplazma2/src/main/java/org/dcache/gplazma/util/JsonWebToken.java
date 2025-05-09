/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2019 - 2022 Deutsches Elektronen-Synchrotron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dcache.gplazma.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JsonWebToken is a bearer token with three parts: a header, a payload and a signature.  This
 * class provides access to well-known header claims, the ability to verify the signature, and a
 * flexible mechanism to extract information from the payload.
 */
public class JsonWebToken {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonWebToken.class);

    private final ObjectMapper mapper = new ObjectMapper();

    // Header values
    private final String typ;
    private final String alg;
    private final String kid;

    private final JsonNode payload;

    private final byte[] unsignedToken;
    private final byte[] signature;

    public static boolean isCompatibleFormat(String token) {
        List<String> elements = Splitter.on('.').limit(3).splitToList(token);
        return elements.size() == 3 && elements.stream().allMatch(JsonWebToken::isBase64Encoded);
    }

    private static boolean isBase64Encoded(String data) {
        try {
            Base64.getUrlDecoder().decode(data);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode decodeToJson(String encoded) throws IOException {
        String data = new String(decodeToBytes(encoded), StandardCharsets.UTF_8);
        return mapper.readValue(data, JsonNode.class);
    }

    private static byte[] decodeToBytes(String data) {
        return Base64.getUrlDecoder().decode(data);
    }

    public JsonWebToken(String token) throws IOException {
        int lastDot = token.lastIndexOf('.');
        checkArgument(lastDot > 0, "Missing '.' in JWT");
        unsignedToken = token.substring(0, lastDot).getBytes(StandardCharsets.US_ASCII);

        List<String> elements = Splitter.on('.').limit(3).splitToList(token);
        checkArgument(elements.size() == 3, "Wrong number of '.' in token");

        JsonNode header = decodeToJson(elements.get(0));
        alg = header.get("alg").textValue();
        typ = getOptionalString(header, "typ");
        kid = getOptionalString(header, "kid");

        payload = decodeToJson(elements.get(1));
        signature = decodeToBytes(elements.get(2));
    }

    private String getOptionalString(JsonNode object, String key) {
        JsonNode node = object.get(key);
        return node == null ? null : node.textValue();
    }

    @Nullable
    public String getKeyIdentifier() {
        return kid;
    }

    private static byte[] transcodeJWTECDSASignatureToDER(byte[] jwsSignature) throws SignatureException {
        int rawLen = jwsSignature.length / 2;
    
        // Find the start of R (skip leading zeros)
        int rStart = 0;
        while (rStart < rawLen && jwsSignature[rStart] == 0) {
            rStart++;
        }
        int rLen = rawLen - rStart;
    
        // Find the start of S (skip leading zeros)
        int sStart = rawLen;
        while (sStart < jwsSignature.length && jwsSignature[sStart] == 0) {
            sStart++;
        }
        int sLen = rawLen - (sStart - rawLen);
    
        int totalLen = 2 + 2 + rLen + 2 + sLen; // SEQUENCE + INTEGER(R) + INTEGER(S)
        int offset = 0;
        byte[] der = new byte[totalLen];
    
        der[offset++] = 0x30; // SEQUENCE
        der[offset++] = (byte) (totalLen - 2);
    
        // INTEGER R
        der[offset++] = 0x02;
        der[offset++] = (byte) rLen;
        System.arraycopy(jwsSignature, rawLen - rLen, der, offset, rLen);
        offset += rLen;
    
        // INTEGER S
        der[offset++] = 0x02;
        der[offset++] = (byte) sLen;
        System.arraycopy(jwsSignature, jwsSignature.length - sLen, der, offset, sLen);
    
        return der;
    }

    public boolean isSignedBy(PublicKey key) {
        try {
            Signature signature = getSignature();
            signature.initVerify(key);
            signature.update(unsignedToken);
            byte[] sig = this.signature;
            if (alg.startsWith("ES")) {
                sig = transcodeJWTECDSASignatureToDER(sig);
            }
            return signature.verify(sig);
        } catch (SignatureException e) {
            LOGGER.warn("Problem verifying signature: {}", e.toString());
            return false;
        } catch (GeneralSecurityException e) {
            LOGGER.warn("Problem verifying signature: {}", e.toString());
            return false;
        }
    }

    private Signature getSignature() throws GeneralSecurityException {
        switch (alg) {
            case "RS256":
                return Signature.getInstance("SHA256withRSA");
            case "ES256":
                return Signature.getInstance("SHA256withECDSA");
            case "ES384":
                return Signature.getInstance("SHA384withECDSA");
            case "ES512":
                return Signature.getInstance("SHA512withECDSA");
            default:
                throw new NoSuchAlgorithmException("Unknown JWT alg " + alg);
        }
    }

    public Optional<Instant> getPayloadInstant(String key) {
        return Optional.ofNullable(payload.get(key))
              .filter(JsonNode::isIntegralNumber)
              .map(JsonNode::asLong)
              .map(Instant::ofEpochSecond);
    }

    public Optional<String> getPayloadString(String key) {
        return Optional.ofNullable(payload.get(key))
              .filter(JsonNode::isTextual)
              .map(JsonNode::textValue);
    }

    public Optional<String> getPayloadValueAsString(String key) {
        return Optional.ofNullable(payload.get(key))
              .map(n -> {
                    try {
                        return mapper.writeValueAsString(n);
                    } catch (JsonProcessingException e) {
                        return "Bad JSON: " + e;
                    }
                  });
    }

    public Map<String,JsonNode> getPayloadMap() {
        return Streams.stream(payload.fields())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    }

    /**
     * A node is either absent, a JSON String, or a JSON Array with JSON String elements. If absent
     * then an empty List is returned.  If present and a JSON String then a List containing only
     * that value is returned.  If present and a JSON Array then a List containing all textual
     * elements is returned; all non-textual elements are ignored.  If the value is not a JSON
     * String or a JSON array then an empty List is returned.
     *
     * @param key the ID of the element to return
     * @return a List of Strings, which may be empty.
     */
    public List<String> getPayloadStringOrArray(String key) {
        return Optional.ofNullable(payload.get(key))
              .filter(n -> n.isArray() || n.isTextual())
              .map(JsonWebToken::toStringList)
              .orElse(Collections.emptyList());
    }

    private static List<String> toStringList(JsonNode node) {
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                  .filter(JsonNode::isTextual) // Non text array elements are simply ignored
                  .map(JsonNode::textValue)
                  .collect(Collectors.toList());
        } else if (node.isTextual()) {
            return Collections.singletonList(node.textValue());
        } else {
            throw new RuntimeException("Unable to convert node " + node
                  + " to List<String>");
        }
    }
}
