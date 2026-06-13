package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a build directive emitted by the AI inside a {@code ```rougebuild} (or legacy
 * {@code ```stepplan}) fence into a concrete {@link StepPlan}.
 *
 * <p>Three shapes are supported, in priority order:
 * <ol>
 *   <li><b>Retrieve</b> — {@code {"use":"rs-latch"}}: returns the library primitive's
 *       hand-authored, verified steps verbatim.</li>
 *   <li><b>Compose</b> — {@code {"parts":[{"use":"id","dx":0,"dy":0,"dz":0}], "steps":[...]}}:
 *       stitches several library primitives together at offsets, then appends optional
 *       extra wiring steps. Part steps are cumulative within each part; the appended
 *       {@code steps} are <i>additive</i> (new blocks only).</li>
 *   <li><b>Custom</b> — {@code {"steps":[...]}} with explicit, <i>cumulative</i> blocks:
 *       a fully generated build for anything not in the library.</li>
 * </ol>
 *
 * All coordinate math is local to the plan; the in-world anchor is applied later by the
 * renderer. Every step's block list is normalised to be cumulative so the hologram can
 * diff consecutive steps to highlight what's new.
 */
public final class BuildDirective {

    private BuildDirective() {}

    public static StepPlan resolve(String rawJson) {
        JsonObject root = JsonParser.parseString(trimToJsonObject(rawJson)).getAsJsonObject();
        String circuit = root.has("circuit") ? root.get("circuit").getAsString() : null;

        // 1. Retrieve a single library build verbatim.
        if (root.has("use") && !root.get("use").isJsonNull()) {
            String id = root.get("use").getAsString();
            CircuitPrimitive p = CircuitLibrary.get(id);
            if (p != null && p.isBuildable()) {
                return circuit == null ? p.toStepPlan() : StepPlan.of(circuit, p.steps());
            }
            // The id is a blueprint (no block data) or unknown. Only error out here if there's
            // no other shape to fall back on — otherwise parts/steps below take over.
            if (!root.has("parts") && !root.has("steps")) {
                throw new IllegalArgumentException(p != null
                        ? "'" + id + "' is a blueprint with no block data — model should emit explicit steps, not {\"use\"}"
                        : "unknown build id '" + id + "'");
            }
        }

        // 2. Compose from parts (+ optional additive wiring steps).
        if (root.has("parts") && root.get("parts").isJsonArray()) {
            return compose(circuit, root.getAsJsonArray("parts"),
                    root.has("steps") && root.get("steps").isJsonArray()
                            ? root.getAsJsonArray("steps") : null);
        }

        // 3. Custom build with explicit cumulative blocks.
        return StepPlan.fromJson(rawJson);
    }

    private static StepPlan compose(String circuit, JsonArray parts, JsonArray extraSteps) {
        List<StepPlan.Step> out = new ArrayList<>();
        // Cumulative blocks contributed by parts already fully placed.
        List<BlockEntry> base = new ArrayList<>();
        int buildableParts = 0;

        for (JsonElement el : parts) {
            if (!el.isJsonObject()) continue;
            JsonObject part = el.getAsJsonObject();
            if (!part.has("use") || part.get("use").isJsonNull()) continue;
            CircuitPrimitive prim = CircuitLibrary.get(part.get("use").getAsString());
            if (prim == null || !prim.isBuildable()) continue;
            buildableParts++;

            int dx = intOr(part, "dx", 0), dy = intOr(part, "dy", 0), dz = intOr(part, "dz", 0);
            String label = part.has("label") ? part.get("label").getAsString() : prim.title();

            List<StepPlan.Step> pSteps = prim.steps();
            for (StepPlan.Step s : pSteps) {
                List<BlockEntry> cumulative = new ArrayList<>(base);
                cumulative.addAll(offset(s.blocks(), dx, dy, dz));
                out.add(new StepPlan.Step(label + ": " + s.title(), s.explanation(), dedupe(cumulative)));
            }
            if (!pSteps.isEmpty()) {
                base = dedupe(concat(base, offset(pSteps.get(pSteps.size() - 1).blocks(), dx, dy, dz)));
            }
        }

        // Appended wiring/finish steps: additive (each lists only its new blocks).
        if (extraSteps != null) {
            List<BlockEntry> acc = new ArrayList<>(base);
            for (JsonElement el : extraSteps) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                String title = s.has("title") ? s.get("title").getAsString() : "Wiring";
                String expl = s.has("explanation") ? s.get("explanation").getAsString() : "";
                if (s.has("blocks") && s.get("blocks").isJsonArray()) {
                    acc = dedupe(concat(acc, readBlocks(s.getAsJsonArray("blocks"))));
                }
                out.add(new StepPlan.Step(title, expl, new ArrayList<>(acc)));
            }
        }

        // None of the referenced parts had block data (e.g. the model composed entirely
        // from blueprint ids). Fail clearly so the player gets an actionable message.
        if (buildableParts == 0 && out.isEmpty()) {
            throw new IllegalArgumentException(
                    "compose referenced no buildable parts — model should use buildable ids or emit explicit steps");
        }

        return StepPlan.of(circuit, out);
    }

    private static List<BlockEntry> offset(List<BlockEntry> blocks, int dx, int dy, int dz) {
        List<BlockEntry> out = new ArrayList<>(blocks.size());
        for (BlockEntry b : blocks) {
            out.add(new BlockEntry(b.x() + dx, b.y() + dy, b.z() + dz, b.block()));
        }
        return out;
    }

    private static List<BlockEntry> readBlocks(JsonArray arr) {
        List<BlockEntry> out = new ArrayList<>(arr.size());
        for (JsonElement be : arr) {
            BlockEntry entry = BlockEntries.parse(be);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    private static List<BlockEntry> concat(List<BlockEntry> a, List<BlockEntry> b) {
        List<BlockEntry> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** Collapses duplicate positions, keeping the last writer (so overlays win). */
    private static List<BlockEntry> dedupe(List<BlockEntry> blocks) {
        Map<Long, BlockEntry> byPos = new LinkedHashMap<>();
        for (BlockEntry b : blocks) {
            byPos.put(key(b.x(), b.y(), b.z()), b);
        }
        return new ArrayList<>(byPos.values());
    }

    private static long key(int x, int y, int z) {
        // Offset to keep small negative coords distinct; builds stay well within range.
        return (((long) (x + 512)) << 22) | (((long) (y + 512)) << 11) | (z + 512);
    }

    private static int intOr(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    /**
     * Trims a fenced body to its outermost JSON object. Models sometimes wrap the JSON with a
     * stray comment line or trailing remark inside the fence ("Here's the plan: { ... }"),
     * which would otherwise make {@link JsonParser} choke. If no braces are found, returns the
     * input trimmed (so the original parse error still surfaces).
     */
    private static String trimToJsonObject(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }
}
