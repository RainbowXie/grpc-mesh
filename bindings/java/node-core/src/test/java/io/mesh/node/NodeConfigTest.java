package io.mesh.node;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Configuration contract tests for {@link NodeConfig} (handshake features,
 * metadata fidelity, snapshot isolation and Android-API-safe collections).
 *
 * <p>Pure Java: this class never touches {@link GrpcMeshNode}, so it runs
 * without the native library. JSON is parsed with a standard parser
 * (Gson) so the assertions check the wire shape, not string equality.
 */
class NodeConfigTest {

    private static final String VALUE_WITH_SURROUNDING_SPACE = "  text  ";

    private static NodeConfig.Builder minimal() {
        return NodeConfig.builder().serverAddress("127.0.0.1:8443").nodeId("n1").token("t1");
    }

    private static JsonObject nodeSection(NodeConfig config) {
        JsonObject root = JsonParser.parseString(config.toJson()).getAsJsonObject();
        return root.getAsJsonObject("node");
    }

    private static JsonArray featureArray(NodeConfig config) {
        return nodeSection(config).getAsJsonArray("features");
    }

    @Test
    void defaultConfigOmitsHandshakeExtensions() {
        JsonObject node = nodeSection(minimal().build());
        assertFalse(node.has("features"), "features must only appear when declared");
        assertFalse(node.has("metadata"), "metadata must only appear when declared");
        assertEquals("n1", node.get("id").getAsString());
        assertEquals("t1", node.get("token").getAsString());
    }

    @Test
    void featuresSerializeAsJsonStringArray() {
        NodeConfig config = minimal().addFeature("DG").addFeature("reporter").build();
        JsonArray features = featureArray(config);
        assertEquals(2, features.size());
        assertEquals("DG", features.get(0).getAsString());
        assertEquals("reporter", features.get(1).getAsString());
    }

    @Test
    void specialCharactersSurviveJsonRoundTrip() {
        String quoted = "say \"hi\"";
        String backslash = "a\\b";
        String newline = "line1\nline2";
        String chinese = "中文值";
        NodeConfig config =
                minimal()
                        .addMetadata("quoted", quoted)
                        .addMetadata("backslash", backslash)
                        .addMetadata("newline", newline)
                        .addMetadata("chinese", chinese)
                        .build();
        JsonObject metadata = nodeSection(config).getAsJsonObject("metadata");
        assertEquals(quoted, metadata.get("quoted").getAsString());
        assertEquals(backslash, metadata.get("backslash").getAsString());
        assertEquals(newline, metadata.get("newline").getAsString());
        assertEquals(chinese, metadata.get("chinese").getAsString());
    }

    @Test
    void featureSpecialCharactersSerializeAsStrings() {
        String tricky = "q\"b\\n\n中";
        NodeConfig config = minimal().addFeature(tricky).build();
        assertEquals(tricky, featureArray(config).get(0).getAsString());
    }

    @Test
    void metadataValuePreservesWhitespaceAndEmptyString() {
        NodeConfig config =
                minimal()
                        .addMetadata("padded", VALUE_WITH_SURROUNDING_SPACE)
                        .addMetadata("empty", "")
                        .build();
        JsonObject metadata = nodeSection(config).getAsJsonObject("metadata");
        assertEquals(VALUE_WITH_SURROUNDING_SPACE, metadata.get("padded").getAsString());
        assertEquals("", metadata.get("empty").getAsString());
        assertTrue(metadata.get("empty").isJsonPrimitive());
    }

    @Test
    void duplicateMetadataKeyUsesLastAssignment() {
        NodeConfig config = minimal().addMetadata("k", "first").addMetadata("k", "second").build();
        assertEquals("second", nodeSection(config).getAsJsonObject("metadata").get("k").getAsString());
    }

    @Test
    void buildCreatesSnapshotDetachedFromBuilder() {
        NodeConfig.Builder builder = minimal().addFeature("alpha").addMetadata("k", "v1");
        NodeConfig first = builder.build();

        builder.addFeature("beta").addMetadata("k", "v2");
        NodeConfig second = builder.build();

        // The earlier config keeps the values captured at its build() call.
        assertArrayEquals(new String[] {"alpha"}, featureStrings(first));
        assertEquals("v1", metadataValue(first, "k"));
        assertArrayEquals(new String[] {"alpha", "beta"}, featureStrings(second));
        assertEquals("v2", metadataValue(second, "k"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unmodifiableSnapshotRejectsExternalMutation() throws Exception {
        // The snapshot fields are private and deliberately have no public
        // accessor; reflection is the only way to assert the *internal*
        // collections are unmodifiable without widening the public API.
        NodeConfig config = minimal().addFeature("alpha").addMetadata("k", "v").build();
        List<String> features = (List<String>) privateField(config, "features");
        assertThrows(UnsupportedOperationException.class, () -> features.add("injected"));
        Map<String, String> metadata = (Map<String, String>) privateField(config, "metadata");
        assertThrows(UnsupportedOperationException.class, () -> metadata.put("injected", "x"));
    }

    private static Object privateField(NodeConfig config, String name) throws Exception {
        Field field = NodeConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(config);
    }

    @Test
    void invalidMetadataKeyOrValueIsRejectedExplicitly() {
        assertThrows(NodeConfigException.class, () -> minimal().addMetadata(null, "v"));
        assertThrows(NodeConfigException.class, () -> minimal().addMetadata("", "v"));
        assertThrows(NodeConfigException.class, () -> minimal().addMetadata("   ", "v"));
        NodeConfigException padded =
                assertThrows(NodeConfigException.class, () -> minimal().addMetadata(" key ", "v"));
        assertTrue(padded.getMessage().contains("key"), padded.getMessage());
        NodeConfigException nullValue =
                assertThrows(NodeConfigException.class, () -> minimal().addMetadata("key", null));
        assertTrue(nullValue.getMessage().contains("key"), nullValue.getMessage());
    }

    @Test
    void unicodeWhitespaceMetadataKeysAreRejectedLikeTheNativeBoundary() {
        // The Rust core trims the Unicode White_Space set, so Java must reject
        // the same padded keys: ASCII String.trim() and Character.isWhitespace
        // would let NBSP/U+0085/U+2007/U+202F through and only fail natively.
        String[] edgeWhitespace = {
            "\u2003", "\u00A0", "\u0085", "\u2007", "\u202F", "\u3000", "\u1680", "\u205F"
        };
        for (String whitespace : edgeWhitespace) {
            assertThrows(
                    NodeConfigException.class,
                    () -> minimal().addMetadata(whitespace + "k", "v"),
                    "leading " + Integer.toHexString(whitespace.charAt(0)) + " must be rejected");
            assertThrows(
                    NodeConfigException.class,
                    () -> minimal().addMetadata("k" + whitespace, "v"),
                    "trailing " + Integer.toHexString(whitespace.charAt(0)) + " must be rejected");
            assertThrows(
                    NodeConfigException.class,
                    () -> minimal().addMetadata(whitespace, "v"),
                    "whitespace-only " + Integer.toHexString(whitespace.charAt(0)) + " must be rejected");
        }
    }

    @Test
    void unicodeWhitespaceInsideMetadataValueIsPreservedVerbatim() {
        // Only key edges are whitespace-checked; values keep every character,
        // including the non-breaking spaces the key check rejects.
        String value = "a\u00A0b\u2007c\u202Fd\u0085e\u2003f";
        NodeConfig config = minimal().addMetadata("k", value).build();
        assertEquals(value, metadataValue(config, "k"));
    }

    @Test
    void reservedFrameworkKeysAreRejectedWithKeyInMessage() {
        for (String reserved : new String[] {"mesh.methods", "platform", "arch"}) {
            NodeConfigException exception =
                    assertThrows(
                            NodeConfigException.class,
                            () -> minimal().addMetadata(reserved, "forged"));
            assertTrue(
                    exception.getMessage().contains(reserved),
                    "message must name the reserved key: " + exception.getMessage());
        }
    }

    @Test
    void invalidFeatureIsRejectedExplicitly() {
        assertThrows(NodeConfigException.class, () -> minimal().addFeature(null));
        assertThrows(NodeConfigException.class, () -> minimal().addFeature("   "));
    }

    @Test
    void unicodeWhitespaceFeatureIsRejectedLikeTheNativeBoundary() {
        // A blank feature must be rejected here too: the native normalizer
        // would otherwise silently drop it after Java accepted it.
        String[] blank = {
            "\u00A0", "\u2003", "\u0085", "\u2007", "\u202F", "\u3000", "\u00A0\u2003\u3000"
        };
        for (String feature : blank) {
            assertThrows(
                    NodeConfigException.class,
                    () -> minimal().addFeature(feature),
                    "blank feature must be rejected");
        }
    }

    @Test
    void featureWithUnicodeWhitespaceEdgeButContentIsAccepted() {
        // Only an all-whitespace tag is rejected; a tag with real content keeps
        // its value here and the Rust normalizer trims it on the wire.
        NodeConfig config = minimal().addFeature("\u00A0DG").build();
        assertEquals("\u00A0DG", featureArray(config).get(0).getAsString());
    }

    private static String[] featureStrings(NodeConfig config) {
        JsonArray features = featureArray(config);
        String[] values = new String[features.size()];
        for (int i = 0; i < features.size(); i++) {
            values[i] = features.get(i).getAsString();
        }
        return values;
    }

    private static String metadataValue(NodeConfig config, String key) {
        JsonElement value = nodeSection(config).getAsJsonObject("metadata").get(key);
        return value.getAsString();
    }
}
