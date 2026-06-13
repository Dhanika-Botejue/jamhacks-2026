package dev.dhanika.rouge.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Defensive parsing for {@link BlockEntry} JSON objects emitted by the AI.
 * <p>
 * Models occasionally produce slightly-off JSON for complex builds: a missing
 * coordinate, a coordinate sent as a string ({@code "x":"0"}), or a block entry
 * with no {@code block} id. Rather than letting one stray entry blow up the whole
 * plan with an NPE (surfaced to the player as a useless "failed to parse"), we
 * default missing coordinates to 0 and skip entries that have no block id.
 */
final class BlockEntries {

    private BlockEntries() {}

    /** Parses one block entry, or returns {@code null} if it has no usable block id. */
    static BlockEntry parse(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject b = el.getAsJsonObject();

        String block = string(b, "block");
        if (block == null || block.isBlank()) return null; // nothing to render

        // Canonicalize the id (spaces→underscores, lowercase, default namespace) so it both
        // renders and matches the placed block in BuildDiff — see BuildSpec.normalizeBlockId.
        return new BlockEntry(intOr(b, "x", 0), intOr(b, "y", 0), intOr(b, "z", 0),
                BuildSpec.normalizeBlockId(block));
    }

    /** Reads an int field, tolerating numeric strings and absent keys. */
    private static int intOr(JsonObject o, String key, int def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return def;
        }
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsString();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
