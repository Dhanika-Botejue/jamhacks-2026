package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A step-by-step build plan emitted by the AI inside a ```stepplan``` fence. */
public final class StepPlan {

    private final String circuit;
    private final List<Step> steps;

    private StepPlan(String circuit, List<Step> steps) {
        this.circuit = circuit;
        this.steps = Collections.unmodifiableList(steps);
    }

    /** Builds a plan directly from assembled steps (used by {@link BuildDirective}). */
    public static StepPlan of(String circuit, List<Step> steps) {
        return new StepPlan(circuit == null || circuit.isBlank() ? "Build" : circuit,
                new ArrayList<>(steps));
    }

    public String circuit() { return circuit; }
    public List<Step> steps() { return steps; }

    public static StepPlan fromJson(String raw) {
        JsonObject root = JsonParser.parseString(raw.trim()).getAsJsonObject();
        String circuit = root.has("circuit") ? root.get("circuit").getAsString() : "Circuit";

        List<Step> steps = new ArrayList<>();
        // Tolerate a missing/malformed "steps" key: yields an empty plan, which callers
        // report as "no steps" rather than crashing with an NPE → "failed to parse".
        JsonArray stepsArr = root.has("steps") && root.get("steps").isJsonArray()
                ? root.getAsJsonArray("steps") : new JsonArray();
        for (JsonElement el : stepsArr) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            String title = s.has("title") ? s.get("title").getAsString() : "Step";
            String explanation = s.has("explanation") ? s.get("explanation").getAsString() : "";

            List<BlockEntry> blocks = new ArrayList<>();
            if (s.has("blocks") && s.get("blocks").isJsonArray()) {
                for (JsonElement be : s.getAsJsonArray("blocks")) {
                    BlockEntry entry = BlockEntries.parse(be);
                    if (entry != null) blocks.add(entry);
                }
            }
            steps.add(new Step(title, explanation, Collections.unmodifiableList(blocks)));
        }
        return new StepPlan(circuit, steps);
    }

    public record Step(String title, String explanation, List<BlockEntry> blocks) {}
}
