package org.openpnp.machine.pnpinventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.NozzleTip;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Upload source definitions through the documented atomic import contract, never through stock APIs. */
final class PnpInventoryCatalogSync {
    private final PnpInventoryActuator actuator;

    PnpInventoryCatalogSync(PnpInventoryActuator actuator) { this.actuator = actuator; }

    String sync() throws Exception {
        String machine = actuator.resolveMachineId();
        if (machine.isEmpty()) { throw new IOException("Select the backend machine before syncing the catalog."); }
        PnpInventoryApi api = actuator.api();
        Path file = actuator.journal("catalog-sync");
        boolean resumed = Files.exists(file);
        JsonObject intent;
        if (resumed) {
            intent = actuator.readIntent(file);
            if (!api.getBaseAddress().equals(PnpInventoryApi.required(intent, "endpoint"))
                    || !machine.equals(PnpInventoryApi.required(intent, "machineId"))) {
                throw new IOException("Restore the original server and machine to finish the saved catalog sync.");
            }
        }
        else {
            JsonObject snapshot = snapshot(machine);
            intent = new JsonObject();
            intent.addProperty("endpoint", api.getBaseAddress());
            intent.addProperty("machineId", machine);
            intent.addProperty("operationId", UUID.randomUUID().toString());
            intent.add("snapshot", snapshot);
            actuator.persistIntent(file, intent);
        }
        JsonObject snapshot = PnpInventoryApi.object(intent.get("snapshot"), "saved catalog snapshot");
        String importId = PnpInventoryApi.required(snapshot, "id");
        Logger.info("PnPInventory {} catalog import {}", resumed ? "resuming" : "starting", importId);
        JsonObject proposal;
        try { proposal = api.request("POST", "imports", snapshot); }
        catch (PnpInventoryApi.HttpFailure e) {
            if ((e.status == 400 || e.status == 422) && !intent.has("apply")) {
                Files.delete(file); // Definitively rejected before application; next click takes a corrected snapshot.
            }
            throw e;
        }
        if (!importId.equals(PnpInventoryApi.required(proposal, "id"))
                || !machine.equals(PnpInventoryApi.required(proposal, "machineId"))) {
            throw new IOException("Backend returned a different catalog import or machine. Saved sync retained.");
        }
        String state = PnpInventoryApi.required(proposal, "state");
        if ("invalid".equals(state)) {
            StringBuilder problems = new StringBuilder("Catalog validation failed; no definitions were applied.");
            JsonElement issues = proposal.get("issues");
            if (issues == null || !issues.isJsonArray()) { throw PnpInventoryApi.invalid("issues"); }
            int count = 0;
            for (JsonElement value : issues.getAsJsonArray()) {
                JsonObject issue = PnpInventoryApi.object(value, "import issue");
                if (count++ < 20) {
                    problems.append("\n").append(safe(PnpInventoryApi.required(issue, "path")))
                            .append(": ").append(safe(PnpInventoryApi.required(issue, "message")));
                }
            }
            Files.delete(file); // A rejected immutable proposal must be corrected using a new import ID.
            throw new IOException(problems.toString());
        }
        if (!"applied".equals(state)) {
            if (!"ready".equals(state)) { throw new IOException("Catalog proposal is not ready. Saved sync retained."); }
            JsonElement issues = proposal.get("issues");
            if (issues == null || !issues.isJsonArray() || issues.getAsJsonArray().size() != 0) {
                throw new IOException("Catalog proposal contains issues. Resolve them in the backend before applying.");
            }
            long revision = PnpInventoryApi.integer(proposal, "proposalRevision");
            if (revision <= 0) { throw PnpInventoryApi.invalid("proposalRevision"); }
            JsonObject request;
            if (intent.has("apply")) {
                request = PnpInventoryApi.object(intent.get("apply"), "saved import apply");
            }
            else {
                request = new JsonObject();
                request.addProperty("id", PnpInventoryApi.required(intent, "operationId"));
                request.addProperty("type", "import.apply");
                request.addProperty("importId", importId);
                JsonObject parameters = new JsonObject();
                parameters.addProperty("proposalRevision", revision);
                request.add("parameters", parameters);
                intent.add("apply", request);
                actuator.persistIntent(file, intent);
            }
            awaitApplied(api, request, importId, file);
        }
        Files.deleteIfExists(file);
        Logger.info("PnPInventory catalog import {} applied", importId);
        return "Catalog synced: " + snapshot.getAsJsonArray("packages").size() + " packages, "
                + snapshot.getAsJsonArray("parts").size() + " parts, "
                + snapshot.getAsJsonArray("nozzleTips").size() + " tips."
                + (resumed ? " Saved snapshot resumed; sync again to upload newer edits." : "");
    }

    private void awaitApplied(PnpInventoryApi api, JsonObject request, String importId, Path file) throws Exception {
        String id = PnpInventoryApi.required(request, "id");
        JsonObject operation;
        try { operation = api.request("POST", "operations", request); }
        catch (PnpInventoryApi.HttpFailure e) {
            if (e.status == 409 || e.status == 412 || e.status == 422 || e.status == 400) {
                Files.delete(file);
                throw new IOException("Catalog apply was rejected. Resolve backend validation/revision conflicts, then Sync catalog again to stage a fresh proposal. No forced overwrite was attempted.");
            }
            throw e;
        }
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            if (!id.equals(PnpInventoryApi.required(operation, "id"))) { throw new IOException("Import operation ID mismatch."); }
            String state = PnpInventoryApi.required(operation, "state");
            if ("succeeded".equals(state)) {
                JsonObject result = PnpInventoryApi.object(operation.get("result"), "import result");
                if (!importId.equals(PnpInventoryApi.required(result, "importId")) || !PnpInventoryApi.bool(result, "applied")) {
                    throw new IOException("Backend did not confirm this catalog was applied. Saved sync retained.");
                }
                return;
            }
            if ("failed".equals(state) || "cancelled".equals(state)) {
                Files.delete(file);
                throw new IOException("Catalog apply failed or was cancelled. Check backend conflicts and retry Sync catalog.");
            }
            if (!java.util.Arrays.asList("queued", "dispatched", "running").contains(state)) {
                throw new IOException("Catalog apply outcome is uncertain. Resolve it in the backend and retry Sync catalog.");
            }
            if (System.nanoTime() >= deadline) { throw new IOException("Catalog sync timed out. Sync catalog will resume the saved request."); }
            try { Thread.sleep(actuator.getPollIntervalMilliseconds()); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            operation = api.request("GET", "operations/" + PnpInventoryApi.segment(id), null);
        }
    }

    static JsonObject snapshot(String machine) throws Exception {
        Configuration config = Configuration.get();
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("id", UUID.randomUUID().toString());
        snapshot.addProperty("schemaVersion", 1);
        snapshot.addProperty("machineId", machine);
        snapshot.addProperty("exportedAt", Instant.now().toString());
        JsonObject units = new JsonObject();
        units.addProperty("length", "mm");
        units.addProperty("rotation", "deg");
        snapshot.add("units", units);
        JsonArray tips = new JsonArray();
        Set<String> tipIds = new HashSet<>();
        for (NozzleTip tip : config.getMachine().getNozzleTips()) {
            if (!tipIds.add(tip.getId())) { throw new IOException("Duplicate nozzle-tip ID: " + tip.getId()); }
            JsonObject value = identity(tip.getId(), tip.getName());
            length(value, "minPartDiameterMm", tip.getMinPartDiameter());
            length(value, "maxPartDiameterMm", tip.getMaxPartDiameter());
            length(value, "maxPartHeightMm", tip.getMaxPartHeight());
            length(value, "diameterLowMm", tip.getDiameterLow());
            length(value, "maxPickToleranceMm", tip.getMaxPickTolerance());
            value.addProperty("pushAndDragAllowed", tip.isPushAndDragAllowed());
            tips.add(value);
        }
        snapshot.add("nozzleTips", tips);
        JsonArray packages = new JsonArray();
        Set<String> packageIds = new HashSet<>();
        for (org.openpnp.model.Package pkg : config.getPackages()) {
            if (!packageIds.add(pkg.getId())) { throw new IOException("Duplicate package ID: " + pkg.getId()); }
            JsonObject value = identity(pkg.getId(), pkg.getId());
            value.addProperty("description", pkg.getDescription() == null ? "" : pkg.getDescription());
            Footprint footprint = pkg.getFootprint();
            if (footprint != null) {
                double factor = new Length(1, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
                number(value, "bodyWidthMm", footprint.getBodyWidth() * factor, false);
                number(value, "bodyHeightMm", footprint.getBodyHeight() * factor, false);
                value.add("footprint", footprint(footprint, factor));
            }
            JsonArray compatible = new JsonArray();
            for (NozzleTip tip : pkg.getCompatibleNozzleTips()) {
                if (!tipIds.contains(tip.getId())) { throw new IOException("Package " + pkg.getId() + " references an unknown nozzle tip."); }
                compatible.add(new com.google.gson.JsonPrimitive(tip.getId()));
            }
            value.add("compatibleNozzleTipSourceIds", compatible);
            JsonObject profile = new JsonObject();
            finite(profile, "pickVacuumLevel", pkg.getPickVacuumLevel());
            finite(profile, "placeBlowOffLevel", pkg.getPlaceBlowOffLevel());
            value.add("profileSettings", profile);
            packages.add(value);
        }
        snapshot.add("packages", packages);
        JsonArray parts = new JsonArray();
        Set<String> partIds = new HashSet<>();
        for (Part part : config.getParts()) {
            if (!partIds.add(part.getId())) { throw new IOException("Duplicate part ID: " + part.getId()); }
            if (part.getPackage() == null || !packageIds.contains(part.getPackage().getId())) {
                throw new IOException("Assign an existing package to OpenPnP part '" + part.getId() + "' before syncing.");
            }
            JsonObject value = identity(part.getId(), part.getName());
            value.addProperty("packageSourceId", part.getPackage().getId());
            value.addProperty("defaultContainerKind", "reel");
            if (part.isPartHeightUnknown()) { value.add("heightMm", JsonNull.INSTANCE); }
            else { length(value, "heightMm", part.getHeight()); }
            JsonObject profile = new JsonObject();
            length(profile, "heightForSafeZMm", part.getHeight().getUnits() == null
                    || part.getThroughBoardDepth().getUnits() == null ? null : part.getHeightForSafeZ());
            length(profile, "throughBoardDepthMm", part.getThroughBoardDepth());
            if (part.getPickRetryCount() < 0 || part.getSpeed() <= 0 || part.getSpeed() > 1) {
                throw new IOException("Invalid retry count or speed for part " + part.getId());
            }
            profile.addProperty("pickRetryCount", part.getPickRetryCount());
            finite(profile, "speedFactor", part.getSpeed());
            profile.addProperty("placementCount", part.getPlacementCount());
            // Source observations only: this does not modify backend assignments.
            JsonArray feeders = new JsonArray();
            for (Feeder feeder : config.getMachine().getFeeders()) {
                if (feeder.getPart() == part) { feeders.add(new com.google.gson.JsonPrimitive(feeder.getId())); }
            }
            profile.add("assignedFeederSourceIds", feeders);
            value.add("profileSettings", profile);
            parts.add(value);
        }
        snapshot.add("parts", parts);
        snapshot.add("feeders", new JsonArray());
        return snapshot;
    }

    private static JsonObject identity(String id, String name) throws IOException {
        if (id == null || id.trim().isEmpty()) { throw new IOException("OpenPnP catalog entry has an empty ID."); }
        JsonObject value = new JsonObject();
        value.addProperty("sourceId", id);
        value.addProperty("name", name == null || name.trim().isEmpty() ? id : name);
        return value;
    }

    private static JsonObject footprint(Footprint source, double factor) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("bodyShape", "rectangle");
        number(result, "innerDimensionMm", source.getInnerDimension() * factor, false);
        number(result, "outerDimensionMm", source.getOuterDimension() * factor, false);
        number(result, "padAcrossMm", source.getPadAcross() * factor, false);
        number(result, "padPitchMm", source.getPadPitch() * factor, false);
        double roundness = source.getPadRoundness();
        result.add("padRoundnessPercent", roundness >= 0 && roundness <= 100
                ? new com.google.gson.JsonPrimitive(roundness) : JsonNull.INSTANCE);
        java.awt.geom.Path2D.Double padGeometry = new java.awt.geom.Path2D.Double();
        // Copy physical pads only: the OpenPnP editor's selected-pad marker is not footprint geometry.
        for (Footprint.Pad pad : source.getPads()) {
            Footprint.Pad physical = new Footprint.Pad();
            physical.setX(pad.getX());
            physical.setY(pad.getY());
            physical.setWidth(pad.getWidth());
            physical.setHeight(pad.getHeight());
            physical.setRotation(pad.getRotation());
            physical.setRoundness(pad.getRoundness());
            padGeometry.append(physical.getShape(), false);
        }
        result.add("padsShape", source.getPads().isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive("custom"));
        result.add("shapeSvgPath", source.getPads().isEmpty() ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(svg(padGeometry, factor)));
        JsonArray pads = new JsonArray();
        Set<String> names = new HashSet<>();
        for (Footprint.Pad pad : source.getPads()) {
            if (pad.getName() == null || pad.getName().isEmpty() || !names.add(pad.getName())) {
                throw new IOException("Footprint pads need unique, nonempty names.");
            }
            JsonObject value = new JsonObject();
            value.addProperty("name", pad.getName());
            finite(value, "xMm", pad.getX() * factor);
            finite(value, "yMm", pad.getY() * factor);
            number(value, "widthMm", pad.getWidth() * factor, true);
            number(value, "heightMm", pad.getHeight() * factor, true);
            finite(value, "rotationDeg", pad.getRotation());
            pads.add(value);
        }
        result.add("pads", pads);
        return result;
    }

    private static String svg(java.awt.Shape shape, double factor) throws IOException {
        java.awt.geom.PathIterator iterator = shape.getPathIterator(java.awt.geom.AffineTransform.getScaleInstance(factor, factor));
        StringBuilder path = new StringBuilder();
        double[] coordinates = new double[6];
        String[] commands = {"M", "L", "Q", "C", "Z"};
        int[] sizes = {2, 2, 4, 6, 0};
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coordinates);
            path.append(commands[type]);
            for (int i = 0; i < sizes[type]; i++) {
                if (!Double.isFinite(coordinates[i])) { throw new IOException("Footprint contains invalid coordinates."); }
                path.append(' ').append(coordinates[i]);
            }
            path.append(' ');
            iterator.next();
        }
        return path.toString().trim();
    }

    private static void length(JsonObject target, String field, Length value) throws IOException {
        if (value == null || value.getUnits() == null) { target.add(field, JsonNull.INSTANCE); }
        else { number(target, field, value.convertToUnits(LengthUnit.Millimeters).getValue(), false); }
    }

    private static void number(JsonObject target, String field, double value, boolean positive) throws IOException {
        if (value < 0 || (positive && value == 0)) { throw new IOException("Invalid catalog dimension: " + field); }
        finite(target, field, value);
    }

    private static void finite(JsonObject target, String field, double value) throws IOException {
        if (!Double.isFinite(value)) { throw new IOException("Invalid catalog value: " + field); }
        target.addProperty(field, value);
    }

    private static String safe(String text) {
        return text.replaceAll("[\\r\\n\\p{Cntrl}]", " ").substring(0, Math.min(text.length(), 300));
    }
}
