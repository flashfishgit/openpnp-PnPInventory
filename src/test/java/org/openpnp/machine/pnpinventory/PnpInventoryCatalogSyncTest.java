package org.openpnp.machine.pnpinventory;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PnpInventoryCatalogSyncTest {
    @TempDir Path directory;
    private HttpServer server;
    private PnpInventoryActuator actuator;
    private Part part;
    private org.openpnp.model.Package pkg;
    private final Map<String, JsonObject> imports = new HashMap<>();
    private final Map<String, JsonObject> operations = new HashMap<>();
    private final Map<String, JsonObject> catalog = new HashMap<>();
    private final AtomicBoolean loseStage = new AtomicBoolean();
    private final AtomicBoolean loseApply = new AtomicBoolean();
    private boolean invalid;
    private boolean conflict;
    private int applied;
    private int requests;

    @BeforeEach void setup() throws Exception {
        Configuration.initialize(directory.toFile());
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        pkg = new org.openpnp.model.Package("0603");
        pkg.setDescription("Chip resistor");
        Configuration.get().addPackage(pkg);
        part = new Part("R10K");
        part.setName("10 kOhm");
        part.setPackage(pkg);
        part.setHeight(new Length(0.5, LengthUnit.Millimeters));
        Configuration.get().addPart(part);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::respond);
        server.start();
        actuator = new PnpInventoryActuator();
        actuator.setPort(server.getAddress().getPort());
        actuator.setMachineId("machine");
        actuator.setPollIntervalMilliseconds(1);
        machine.addActuator(actuator);
    }

    @AfterEach void stop() { server.stop(0); }

    private void respond(HttpExchange exchange) throws IOException {
        requests++;
        String path = exchange.getRequestURI().getPath().substring("/api/v1/".length());
        JsonObject result = new JsonObject();
        int status = 200;
        if ("POST".equals(exchange.getRequestMethod()) && path.equals("imports")) {
            JsonObject body = read(exchange);
            String id = body.get("id").getAsString();
            JsonObject previous = imports.putIfAbsent(id, body);
            if (previous != null && !previous.equals(body)) { status = 409; }
            else if (loseStage.getAndSet(false)) { status = 503; }
            else {
                result.addProperty("id", id);
                result.addProperty("machineId", "machine");
                result.addProperty("state", invalid ? "invalid" : "ready");
                result.addProperty("proposalRevision", 7);
                result.add("issues", new JsonParser().parse(invalid
                        ? "[{\"path\":\"parts[0]\",\"code\":\"mapping-conflict\",\"message\":\"Resolve the mapping\"}]" : "[]"));
            }
        }
        else if (path.equals("operations") && "POST".equals(exchange.getRequestMethod())) {
            JsonObject body = read(exchange);
            String id = body.get("id").getAsString();
            if (!"import.apply".equals(body.get("type").getAsString())) { status = 422; }
            else if (conflict) { status = 409; }
            else {
                JsonObject previous = operations.putIfAbsent(id, body);
                if (previous != null && !previous.equals(body)) { status = 409; }
                else {
                    if (previous == null) {
                        JsonObject snapshot = imports.get(body.get("importId").getAsString());
                        for (String kind : new String[] {"parts", "packages", "nozzleTips"}) {
                            for (com.google.gson.JsonElement value : snapshot.getAsJsonArray(kind)) {
                                JsonObject item = value.getAsJsonObject();
                                catalog.put(kind + ":" + item.get("sourceId").getAsString(), item);
                            }
                        }
                        applied++;
                    }
                    if (loseApply.getAndSet(false)) { status = 503; }
                    else {
                        result.addProperty("id", id);
                        result.addProperty("state", "queued");
                    }
                }
            }
        }
        else if (path.startsWith("operations/") && "GET".equals(exchange.getRequestMethod())) {
            String id = path.substring("operations/".length());
            result.addProperty("id", id);
            result.addProperty("state", "succeeded");
            JsonObject appliedResult = new JsonObject();
            appliedResult.addProperty("importId", operations.get(id).get("importId").getAsString());
            appliedResult.addProperty("applied", true);
            result.add("result", appliedResult);
        }
        else { status = 404; }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonObject read(HttpExchange exchange) throws IOException {
        return new JsonParser().parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test void createsThenUpdatesUsingStableSourceIdsWithoutStockOrAssignments() throws Exception {
        assertTrue(actuator.syncCatalog().contains("1 packages, 1 parts"));
        assertEquals(2, catalog.size());
        part.setName("Updated resistor");
        pkg.setDescription("Updated footprint description");
        actuator.syncCatalog();
        assertEquals(2, catalog.size());
        assertEquals("Updated resistor", catalog.get("parts:R10K").get("name").getAsString());
        assertEquals("Updated footprint description", catalog.get("packages:0603").get("description").getAsString());
        for (JsonObject snapshot : imports.values()) {
            assertEquals(0, snapshot.getAsJsonArray("feeders").size());
            assertFalse(snapshot.has("containers"));
            assertFalse(snapshot.getAsJsonArray("parts").get(0).getAsJsonObject().has("quantity"));
        }
        assertFalse(java.nio.file.Files.exists(actuator.journal("catalog-sync")));
    }

    @Test void lostImportResponseReplaysOriginalSnapshotEvenAfterLocalEdit() throws Exception {
        loseStage.set(true);
        assertThrows(IOException.class, actuator::syncCatalog);
        part.setName("New local name");
        assertTrue(actuator.syncCatalog().contains("Saved snapshot resumed"));
        assertEquals(1, imports.size());
        assertEquals("10 kOhm", catalog.get("parts:R10K").get("name").getAsString());
        actuator.syncCatalog();
        assertEquals("New local name", catalog.get("parts:R10K").get("name").getAsString());
    }

    @Test void lostApplyResponseDoesNotApplyTwice() throws Exception {
        loseApply.set(true);
        assertThrows(IOException.class, actuator::syncCatalog);
        actuator.syncCatalog();
        assertEquals(1, imports.size());
        assertEquals(1, operations.size());
        assertEquals(1, applied);
    }

    @Test void validationFailureAndDefinitiveConflictAllowFreshCorrectedProposal() throws Exception {
        invalid = true;
        IOException failure = assertThrows(IOException.class, actuator::syncCatalog);
        assertTrue(failure.getMessage().contains("parts[0]"));
        assertTrue(operations.isEmpty());
        invalid = false;
        conflict = true;
        assertThrows(IOException.class, actuator::syncCatalog);
        assertFalse(java.nio.file.Files.exists(actuator.journal("catalog-sync")));
        conflict = false;
        actuator.syncCatalog();
        assertEquals(3, imports.size());
        assertEquals(1, applied);
    }

    @Test void wrongMachineCannotReplayAndOrphanPartFailsBeforeNetwork() throws Exception {
        part.setPackage(null);
        assertThrows(IOException.class, actuator::syncCatalog);
        assertEquals(0, requests);
        part.setPackage(pkg);
        loseStage.set(true);
        assertThrows(IOException.class, actuator::syncCatalog);
        int before = requests;
        actuator.setMachineId("other-machine");
        assertThrows(IOException.class, actuator::syncCatalog);
        assertEquals(before, requests);
    }

    @Test void convertsGeometryAndIncludesNozzleCompatibilityAndProfiles() throws Exception {
        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        tip.setName("Small tip");
        Configuration.get().getMachine().addNozzleTip(tip);
        pkg.addCompatibleNozzleTip(tip);
        Footprint footprint = pkg.getFootprint();
        footprint.setUnits(LengthUnit.Inches);
        footprint.setBodyWidth(0.1);
        footprint.setBodyHeight(0.05);
        Footprint.Pad pad = new Footprint.Pad();
        pad.setName("1");
        pad.setWidth(0.01);
        pad.setHeight(0.02);
        pad.setX(-0.03);
        footprint.addPad(pad);
        actuator.syncCatalog();
        JsonObject exported = catalog.get("packages:0603");
        assertEquals(2.54, exported.get("bodyWidthMm").getAsDouble(), 1e-8);
        JsonObject exportedPad = exported.getAsJsonObject("footprint").getAsJsonArray("pads").get(0).getAsJsonObject();
        assertEquals(0.254, exportedPad.get("widthMm").getAsDouble(), 1e-8);
        assertEquals(-0.762, exportedPad.get("xMm").getAsDouble(), 1e-8);
        assertEquals(tip.getId(), exported.getAsJsonArray("compatibleNozzleTipSourceIds").get(0).getAsString());
        assertTrue(exported.has("profileSettings"));
        assertTrue(catalog.get("parts:R10K").has("profileSettings"));
        assertTrue(catalog.containsKey("nozzleTips:" + tip.getId()));
    }
}
