package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A high-level design outline proposed by the AI before committing to a full build directive. */
public record PlanningOutline(
        String circuit,
        List<Part> parts,
        String footprint,
        String notes,
        List<BlockEntry> preview   // optional flat block list for the ghost hologram; may be empty
) {

    public record Part(String name, String description) {}

    public static PlanningOutline fromJson(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        String circuit = obj.has("circuit") ? obj.get("circuit").getAsString() : "Design";
        String footprint = obj.has("footprint") ? obj.get("footprint").getAsString() : "";
        String notes = obj.has("notes") ? obj.get("notes").getAsString() : "";

        List<Part> parts = new ArrayList<>();
        if (obj.has("parts")) {
            JsonArray arr = obj.getAsJsonArray("parts");
            for (var el : arr) {
                JsonObject p = el.getAsJsonObject();
                String name = p.has("name") ? p.get("name").getAsString() : "";
                String desc = p.has("description") ? p.get("description").getAsString() : "";
                parts.add(new Part(name, desc));
            }
        }

        List<BlockEntry> preview = new ArrayList<>();
        if (obj.has("preview")) {
            JsonArray arr = obj.getAsJsonArray("preview");
            for (var el : arr) {
                JsonObject b = el.getAsJsonObject();
                int x = b.has("x") ? b.get("x").getAsInt() : 0;
                int y = b.has("y") ? b.get("y").getAsInt() : 0;
                int z = b.has("z") ? b.get("z").getAsInt() : 0;
                String block = b.has("block") ? b.get("block").getAsString() : "minecraft:stone";
                preview.add(new BlockEntry(x, y, z, block));
            }
        }

        return new PlanningOutline(circuit,
                Collections.unmodifiableList(parts),
                footprint,
                notes,
                Collections.unmodifiableList(preview));
    }
}
