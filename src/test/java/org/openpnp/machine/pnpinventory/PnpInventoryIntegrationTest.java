package org.openpnp.machine.pnpinventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PnpInventoryIntegrationTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private ReferenceMachine machine;
    private PnpInventoryActuator actuator;
    private final List<String> operationIds = new ArrayList<>();
    private final List<JsonObject> requests = new ArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicBoolean loseResponse = new AtomicBoolean();
    private String operationState = "succeeded";
    private String assignedPart = "part-backend";
    private boolean confirmed = true;
    private String apiVersion = "v1";
    private final java.util.Set<String> scanIds = new java.util.HashSet<>();
    private String scanState = "succeeded";
    private boolean scanEmpty;
    private int assignmentStatus = 200;
    private boolean duplicateSlot;
    private String auth;
    private int loginCount;
    private String logoutMethod;
    private boolean failLogin;
    private boolean duplicateJob;
    private boolean duplicateMachine;
    private String jobState = "running";
    private long currentAllocation = 3;

    @BeforeEach
    void setup() throws Exception {
        Configuration.initialize(directory.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::respond);
        server.start();
        actuator = new PnpInventoryActuator();
        actuator.setPort(server.getAddress().getPort());
        actuator.setMachineId("machine");
        actuator.setAccessToken("test-token");
        actuator.setPollIntervalMilliseconds(1);
        machine.addActuator(actuator);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        auth = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath().substring("/api/v1/".length());
        String body;
        int status = 200;
        if (path.equals("health")) {
            body = "{\"status\":\"ok\",\"apiVersion\":\"" + apiVersion
                    + "\",\"database\":\"connected\",\"fbus\":\"connected\"}";
        }
        else if (path.equals("sessions")) {
            loginCount++;
            JsonObject credentials = new JsonParser().parse(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (failLogin || !"operator".equals(credentials.get("username").getAsString())
                    || !"secret-password".equals(credentials.get("password").getAsString())) {
                status = 401;
                body = "{}";
            }
            else {
                status = 201;
                body = "{\"id\":\"session\",\"accessToken\":\"login-token\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\",\"revoked\":false}";
            }
        }
        else if (path.equals("sessions/session")) {
            logoutMethod = exchange.getRequestMethod();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        else if (path.equals("machines")) {
            String item = "{\"id\":\"machine\",\"name\":\"Workbench\"}";
            body = "{\"items\":[" + item + (duplicateMachine ? "," + item : "") + "],\"nextCursor\":null}";
        }
        else if (path.equals("sync-snapshots") || path.startsWith("sync-snapshots/")) {
            if (exchange.getRequestMethod().equals("POST")) {
                body = "{}";
            }
            else {
                String item = "{\"collection\":\"jobs\",\"resourceId\":\"running-job\",\"data\":{"
                        + "\"name\":\"Board run\",\"machineId\":\"machine\",\"state\":\"running\"}}";
                body = "{\"items\":[" + item + (duplicateJob ? "," + item : "") + "],\"nextCursor\":null}";
            }
        }
        else if (path.equals("jobs/running-job")) {
            body = "{\"id\":\"running-job\",\"name\":\"Board run\",\"machineId\":\"machine\","
                    + "\"state\":\"" + jobState + "\"}";
        }
        else if (path.equals("jobs/running-job/allocations")) {
            body = "{\"jobId\":\"running-job\",\"revision\":" + currentAllocation + "}";
        }
        else if (path.equals("feeders")) {
            boolean secondPage = exchange.getRequestURI().getQuery().contains("cursor=");
            String feeder = "{\"id\":\"feeder-backend\",\"kind\":\"fbus\",\"fbusAddress\":7,"
                    + "\"busId\":\"bus\",\"online\":true,\"enabled\":true,\"commissioningState\":\"ready\"}";
            body = secondPage ? "{\"items\":[" + feeder + (duplicateSlot ? "," + feeder : "")
                    + "],\"nextCursor\":null}" : "{\"items\":[],\"nextCursor\":\"page 2\"}";
        }
        else if (path.equals("feeders/feeder-backend")) {
            body = "{\"id\":\"feeder-backend\",\"kind\":\"fbus\",\"fbusAddress\":7,"
                    + "\"busId\":\"bus\",\"online\":true,\"enabled\":true,\"commissioningState\":\"ready\"}";
        }
        else if (path.endsWith("/assignment")) {
            status = assignmentStatus;
            body = "{\"containerId\":\"container\",\"revision\":4,\"appliedRevision\":4,"
                    + "\"feedPitchMm\":4,\"state\":\"" + (confirmed ? "confirmed" : "pending") + "\"}";
        }
        else if (path.equals("containers/container")) {
            body = "{\"partId\":\"" + assignedPart + "\",\"status\":\"active\"}";
        }
        else if (path.startsWith("parts/")) {
            body = "{\"packageId\":\"package-backend\"}";
        }
        else if (path.equals("machines/machine/mappings")) {
            body = "{\"items\":[{\"kind\":\"part\",\"id\":\"part-backend\",\"sourceId\":\"R-10K\"},"
                    + "{\"kind\":\"package\",\"id\":\"package-backend\",\"sourceId\":\"0603\"}]}";
        }
        else if (path.equals("operations") || path.startsWith("operations/")) {
            String id;
            if (exchange.getRequestMethod().equals("POST")) {
                JsonObject request = new JsonParser().parse(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                id = request.get("id").getAsString();
                operationIds.add(id);
                requests.add(request);
                if ("bus.scan".equals(request.get("type").getAsString())) { scanIds.add(id); }
                if (loseResponse.getAndSet(false)) {
                    status = 503;
                    body = "{\"detail\":\"Response lost after acceptance\"}";
                }
                else {
                    body = "{\"id\":\"" + id + "\",\"state\":\"queued\"}";
                }
            }
            else {
                polls.incrementAndGet();
                id = path.substring("operations/".length());
                if (scanIds.contains(id)) {
                    body = "{\"id\":\"" + id + "\",\"state\":\"" + scanState
                            + "\",\"result\":{\"discoveredFeederIds\":" + (scanEmpty ? "[]" : "[\"feeder-backend\"]")
                            + ",\"offlineFeederIds\":[]}}";
                }
                else { body = "{\"id\":\"" + id + "\",\"state\":\"" + operationState + "\"}"; }
            }
        }
        else {
            status = 404;
            body = "{\"detail\":\"Not found\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private Part addPart() {
        org.openpnp.model.Package packag = new org.openpnp.model.Package("0603");
        Configuration.get().addPackage(packag);
        Part part = new Part("R-10K");
        part.setPackage(packag);
        Configuration.get().addPart(part);
        return part;
    }

    private PnpInventorySlotFeeder feeder() throws Exception {
        PnpInventorySlotFeeder feeder = new PnpInventorySlotFeeder();
        feeder.setInventoryActuator(actuator);
        feeder.setSlot(7);
        machine.addFeeder(feeder);
        return feeder;
    }

    @Test
    void scanSyncAssignsDetectedPartsAndClearsEmptyPlaceholders() throws Exception {
        PnpInventorySlotFeeder occupied = feeder();
        Part expected = addPart();
        PnpInventorySlotFeeder empty = new PnpInventorySlotFeeder();
        empty.setSlot(8);
        empty.setInventoryActuator(actuator);
        empty.setPart(expected);
        machine.addFeeder(empty);
        String summary = actuator.syncAllSlots();
        assertSame(expected, occupied.getPart());
        assertNull(empty.getPart());
        assertTrue(summary.contains("cleared 1"));
        assertEquals("bus.scan", requests.get(0).get("type").getAsString());
        assertEquals("bus", requests.get(0).get("busId").getAsString());
        assertEquals(1, requests.size()); // Scan only; no feed operation.
        scanEmpty = true;
        actuator.syncAllSlots();
        assertNull(occupied.getPart());
    }

    @Test
    void scanFailureOrAssignmentErrorNeverClearsExistingComponents() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        Part original = addPart();
        feeder.setPart(original);
        scanState = "failed";
        assertThrows(IOException.class, actuator::syncAllSlots);
        assertSame(original, feeder.getPart());
        scanState = "succeeded";
        assignmentStatus = 503;
        assertThrows(IOException.class, actuator::syncAllSlots);
        assertSame(original, feeder.getPart());
        assignmentStatus = 404;
        actuator.syncAllSlots();
        assertNull(feeder.getPart()); // Explicitly unassigned is different from a network error.
    }

    @Test
    void scanRetryReusesSavedOperationAndDoesNotAffectOtherActuators() throws Exception {
        feeder();
        Part part = addPart();
        PnpInventorySlotFeeder unrelated = new PnpInventorySlotFeeder();
        unrelated.setSlot(8);
        unrelated.setPart(part);
        machine.addFeeder(unrelated);
        loseResponse.set(true);
        assertThrows(IOException.class, actuator::syncAllSlots);
        actuator.syncAllSlots();
        assertEquals(operationIds.get(0), operationIds.get(1));
        assertSame(part, unrelated.getPart());
    }

    @Test
    void healthAndDiscoveryDoNotFeedOrChangeConfiguration() throws Exception {
        assertTrue(actuator.testConnection().contains("API v1 supported"));
        assertNull(auth); // Health can be checked before login.
        assertEquals("Workbench", actuator.discoverMachines().get(0).name);
        java.util.List<PnpInventoryActuator.SlotChoice> slots = actuator.discoverSlots();
        assertEquals(1, slots.size());
        assertEquals(7, slots.get(0).slot);
        assertTrue(slots.get(0).description.contains("part-backend"));
        assertTrue(slots.get(0).description.contains("container"));
        assertEquals("machine", actuator.getMachineId());
        assertTrue(requests.isEmpty());
        apiVersion = "v2";
        assertThrows(IOException.class, actuator::testConnection);
    }

    @Test
    void syncPreviewIsReadOnlyAndRejectsChangedBackendBeforeApply() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        Part local = addPart();
        PnpInventorySlotFeeder.ComponentSync plan = feeder.previewComponent();
        assertNull(feeder.getPart());
        assertTrue(plan.describe().contains("R-10K"));
        assertTrue(plan.describe().contains("0603"));
        assignedPart = "changed-part";
        assertThrows(IOException.class, () -> PnpInventorySlotFeeder.applySync(java.util.Collections.singletonList(plan)));
        assertNull(feeder.getPart());
        assignedPart = "part-backend";
        PnpInventorySlotFeeder.applySync(java.util.Collections.singletonList(feeder.previewComponent()));
        assertSame(local, feeder.getPart());
        assertTrue(requests.isEmpty());
    }

    @Test
    void bulkSyncValidatesEveryPlanBeforeChangingAnyFeeder() throws Exception {
        PnpInventorySlotFeeder first = feeder();
        addPart();
        PnpInventorySlotFeeder.ComponentSync firstPlan = first.previewComponent();
        PnpInventoryActuator secondConnection = new PnpInventoryActuator();
        secondConnection.setPort(server.getAddress().getPort());
        secondConnection.setMachineId("machine");
        machine.addActuator(secondConnection);
        PnpInventorySlotFeeder second = new PnpInventorySlotFeeder();
        second.setInventoryActuator(secondConnection);
        second.setSlot(7);
        machine.addFeeder(second);
        PnpInventorySlotFeeder.ComponentSync secondPlan = second.previewComponent();
        second.setSlot(8);
        assertThrows(IOException.class, () -> PnpInventorySlotFeeder.applySync(java.util.Arrays.asList(firstPlan, secondPlan)));
        assertNull(first.getPart());
        assertNull(second.getPart());
        assertTrue(requests.isEmpty());
    }

    @Test
    void pendingStatusIsReadOnlyAndPreflightStopsUntilRecovery() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        addPart();
        feeder.syncComponent();
        Job job = new Job();
        job.setFile(directory.resolve("Board run.job.xml").toFile());
        feeder.preflight(job);
        assertEquals("running-job", actuator.getJobId());
        assertTrue(feeder.jobStatus().contains("Board run"));
        assertTrue(requests.isEmpty());
        assertEquals("none", feeder.recoveryState());
        loseResponse.set(true);
        assertThrows(IOException.class, () -> actuator.feed(feeder.getId(), 7, actuator.getAssignment(7), false));
        int sent = requests.size();
        assertEquals("succeeded", feeder.recoveryState());
        assertEquals(sent, requests.size());
        assertThrows(IOException.class, () -> feeder.preflight(job));
        assertEquals(sent, requests.size());
    }

    @Test
    void slotSelectionReportsConflictsBeforeApplying() throws Exception {
        PnpInventorySlotFeeder first = feeder();
        PnpInventorySlotFeeder second = new PnpInventorySlotFeeder();
        second.setInventoryActuator(actuator);
        second.setSlot(8);
        machine.addFeeder(second);
        assertTrue(second.slotConflict(actuator, 7).contains(first.getName()));
        assertEquals("", second.slotConflict(actuator, 8));
        assertEquals(8, second.getSlot());
    }

    @Test
    void loginObtainsReusableTokenWithoutPersistingPasswordAndLogoutRevokesIt() throws Exception {
        actuator.setUsername("operator");
        actuator.login("secret-password");
        assertNull(auth);
        assertEquals("login-token", actuator.getAccessToken());
        actuator.getAssignment(7);
        actuator.getAssignment(7);
        assertEquals(1, loginCount);
        assertEquals("Bearer login-token", auth);
        StringWriter xml = new StringWriter();
        Configuration.createSerializer().write(actuator, xml);
        assertFalse(xml.toString().contains("secret-password"));
        PnpInventoryActuator restored = Configuration.createSerializer().read(PnpInventoryActuator.class, xml.toString());
        assertEquals("login-token", restored.getAccessToken());
        restored.logout();
        assertEquals("DELETE", logoutMethod);
        assertEquals("", restored.getAccessToken());
    }

    @Test
    void rejectedLoginIsNotRetriedAndDoesNotReplaceExistingToken() {
        actuator.setUsername("operator");
        failLogin = true;
        assertThrows(IOException.class, () -> actuator.login("secret-password"));
        assertEquals(1, loginCount);
        assertEquals("test-token", actuator.getAccessToken());
    }

    @Test
    void nameLookupLoadsIdentityAndRefreshesAllocationOnlyForNewOperations() throws Exception {
        actuator.setJobName("Board run");
        actuator.selectJobByName();
        assertEquals("running-job", actuator.getJobId());
        assertEquals(3, actuator.getAllocationRevision());
        PnpInventoryActuator.Assignment assignment = actuator.getAssignment(7);
        actuator.feed("job", 7, assignment, true);
        currentAllocation = 4;
        actuator.feed("job", 7, null, true);
        assertEquals(3, requests.get(1).getAsJsonObject("parameters").get("expectedAllocationRevision").getAsInt());
        actuator.acknowledgeFeed("job");
        actuator.feed("job", 7, assignment, true);
        assertEquals(4, requests.get(2).getAsJsonObject("parameters").get("expectedAllocationRevision").getAsInt());
    }

    @Test
    void ambiguousOrMissingJobNameIsRejectedAndFinishedJobDoesNotFeed() throws Exception {
        actuator.setJobName("Board run");
        duplicateJob = true;
        assertThrows(IOException.class, actuator::selectJobByName);
        assertTrue(actuator.getJobId().isEmpty());
        duplicateJob = false;
        actuator.setJobName("Missing");
        assertThrows(IOException.class, actuator::selectJobByName);
        actuator.setJobName("Board run");
        actuator.selectJobByName();
        jobState = "finished";
        assertThrows(IOException.class, () -> actuator.feed("job", 7, actuator.getAssignment(7), true));
        assertTrue(requests.isEmpty());
    }

    @Test
    void manualFeedWorksEvenWhenSelectedJobIsFinished() throws Exception {
        actuator.setJobName("Board run");
        actuator.selectJobByName();
        jobState = "finished";
        actuator.feed("manual", 7, actuator.getAssignment(7), false);
        assertFalse(requests.get(0).getAsJsonObject("parameters").has("jobId"));
    }

    @Test
    void pollsCompletionAndAuthenticatesWithManualContract() throws Exception {
        PnpInventoryActuator.Assignment assignment = actuator.getAssignment(7);
        actuator.feed("test", 7, assignment, false);
        assertEquals("Bearer test-token", auth);
        assertEquals(1, polls.get());
        JsonObject parameters = requests.get(0).getAsJsonObject("parameters");
        assertEquals(1, parameters.get("quantity").getAsInt());
        assertEquals(4, parameters.get("expectedAssignmentRevision").getAsInt());
        assertFalse(parameters.has("jobId"));
        assertTrue(actuator.hasPendingFeed("test"));
        actuator.acknowledgeFeed("test");
        assertFalse(actuator.hasPendingFeed("test"));
    }

    @Test
    void lostResponseReplaysSameDurableRequestAfterReload() throws Exception {
        loseResponse.set(true);
        assertThrows(IOException.class, () -> actuator.feed("test", 7, actuator.getAssignment(7), false));
        StringWriter xml = new StringWriter();
        Configuration.createSerializer().write(actuator, xml);
        PnpInventoryActuator restored = Configuration.createSerializer().read(PnpInventoryActuator.class, xml.toString());
        restored.feed("test", 7, null, false);
        assertEquals(2, operationIds.size());
        assertEquals(operationIds.get(0), operationIds.get(1));
        assertEquals(requests.get(0), requests.get(1));
        assertTrue(restored.hasPendingFeed("test"));
    }

    @Test
    void uncertainAndTimeoutKeepOriginalOperation() throws Exception {
        operationState = "uncertain";
        assertThrows(IOException.class, () -> actuator.feed("test", 7, actuator.getAssignment(7), false));
        assertTrue(actuator.hasPendingFeed("test"));
        operationState = "dispatched";
        assertThrows(IOException.class, () -> actuator.feed("test", 7, null, false, 10));
        assertEquals(operationIds.get(0), operationIds.get(1));
    }

    @Test
    void jobRequiresContextAndIncludesStableCycle() throws Exception {
        PnpInventoryActuator.Assignment assignment = actuator.getAssignment(7);
        assertThrows(IOException.class, () -> actuator.feed("job", 7, assignment, true));
        assertTrue(requests.isEmpty());
        actuator.setJobId("running-job");
        actuator.setAllocationRevision(3);
        actuator.feed("job", 7, assignment, true);
        actuator.feed("job", 7, null, true);
        JsonObject parameters = requests.get(0).getAsJsonObject("parameters");
        assertEquals("running-job", parameters.get("jobId").getAsString());
        assertEquals(3, parameters.get("expectedAllocationRevision").getAsInt());
        assertEquals(requests.get(0), requests.get(1));
        assertNotNull(parameters.get("cycleId"));
    }

    @Test
    void rejectsAmbiguousSlotAndUnconfirmedAssignment() {
        duplicateSlot = true;
        assertThrows(IOException.class, () -> actuator.getAssignment(7));
        duplicateSlot = false;
        confirmed = false;
        assertThrows(IOException.class, () -> actuator.getAssignment(7));
    }

    @Test
    void syncRequiresExistingPartAndPackageThenAppliesMapping() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        Part original = new Part("original");
        feeder.setPart(original);
        assertThrows(IOException.class, feeder::syncComponent);
        assertSame(original, feeder.getPart());
        Part part = addPart();
        feeder.syncComponent();
        assertSame(part, feeder.getPart());
        assertEquals(4, feeder.getPartPitch().getValue());
        assertTrue(requests.isEmpty());
    }

    @Test
    void cloneOffsetsAllLocationsAndCopiesIndependentVisionPipeline() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 100, 100, 2, 0));
        feeder.setHole1Location(new Location(LengthUnit.Millimeters, 98, 103, 2, 0));
        feeder.setHole2Location(new Location(LengthUnit.Millimeters, 102, 103, 2, 0));
        feeder.setFeedAfterPick(true);
        feeder.setFeedDelayMilliseconds(123);
        feeder.setFeedCompletionTimeoutMilliseconds(23456);
        PnpInventorySlotFeeder next = feeder.createNewInRow();
        assertEquals(8, next.getSlot());
        assertEquals(84, next.getLocation().getY());
        assertEquals(87, next.getHole1Location().getY());
        assertEquals(87, next.getHole2Location().getY());
        assertEquals(2, next.getLocation().getZ());
        assertTrue(next.isFeedAfterPick());
        assertEquals(123, next.getFeedDelayMilliseconds());
        assertEquals(23456, next.getFeedCompletionTimeoutMilliseconds());
        assertNotSame(feeder.getPipeline(), next.getPipeline());
        assertEquals(feeder.getPipeline().toXmlString(), next.getPipeline().toXmlString());
        assertNull(next.getPart());
        assertFalse(next.isEnabled());
        assertThrows(IOException.class, feeder::createNewInRow);
        PnpInventorySlotFeeder third = next.createNewInRow();
        assertEquals(9, third.getSlot());
        assertEquals(68, third.getLocation().getY());
    }

    @Test
    void settingsSurviveXmlRoundTrip() throws Exception {
        PnpInventorySlotFeeder feeder = feeder();
        addPart();
        feeder.syncComponent();
        StringWriter xml = new StringWriter();
        Configuration.createSerializer().write(feeder, xml);
        PnpInventorySlotFeeder restored = Configuration.createSerializer().read(PnpInventorySlotFeeder.class, xml.toString());
        assertEquals(7, restored.getSlot());
        assertSame(actuator, restored.getInventoryActuator());
        assertEquals(16, restored.getRowSpacingMm());
        assertEquals(1, restored.getPartsPerFeedCycle());
    }

    private class VisionlessFeeder extends PnpInventorySlotFeeder {
        @Override
        public void assertCalibrated(boolean tapeFeed) {
        }
    }

    @Test
    void slotFeedsOncePerPartAndReverseModeWaitsUntilPostPick() throws Exception {
        addPart();
        VisionlessFeeder feeder = new VisionlessFeeder();
        feeder.setInventoryActuator(actuator);
        feeder.setSlot(7);
        machine.addFeeder(feeder);
        feeder.syncComponent();
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getHead()).thenReturn(mock(Head.class));
        feeder.feed(nozzle);
        assertEquals(1, requests.size());
        feeder.postPick(nozzle);
        assertEquals(1, requests.size());
        feeder.setFeedAfterPick(true);
        feeder.feed(nozzle);
        assertEquals(1, requests.size());
        feeder.postPick(nozzle);
        assertEquals(2, requests.size());
        feeder.postPick(nozzle);
        assertEquals(2, requests.size());
        assertFalse(feeder.canTakeBackPart());
        assertThrows(IOException.class, feeder::resumePendingFeed);
    }

    @Test
    void reverseTimeoutCanResumeOnlyTheSavedOperation() throws Exception {
        addPart();
        VisionlessFeeder feeder = new VisionlessFeeder();
        feeder.setInventoryActuator(actuator);
        feeder.setSlot(7);
        feeder.setFeedAfterPick(true);
        machine.addFeeder(feeder);
        feeder.syncComponent();
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getHead()).thenReturn(mock(Head.class));
        feeder.feed(nozzle);
        assertThrows(IOException.class, feeder::resumePendingFeed);
        assertTrue(requests.isEmpty());
        loseResponse.set(true);
        assertThrows(IOException.class, () -> feeder.postPick(nozzle));
        assertThrows(IOException.class, () -> feeder.feed(nozzle));
        assertThrows(IllegalStateException.class, () -> feeder.setSlot(8));
        assertThrows(IllegalStateException.class, () -> feeder.setInventoryActuator(null));
        assertThrows(IllegalStateException.class, () -> feeder.setFeedAfterPick(false));
        feeder.resumePendingFeed();
        assertEquals(operationIds.get(0), operationIds.get(1));
        assertFalse(actuator.hasPendingFeed(feeder.getId()));
        assertEquals(1, feeder.getFeedCount());
    }

    @Test
    void clearRequiresBackendResolution() throws Exception {
        operationState = "uncertain";
        assertThrows(IOException.class, () -> actuator.feed("test", 7, actuator.getAssignment(7), false));
        assertThrows(IOException.class, () -> actuator.clearResolvedFeed("test", 7));
        assertTrue(actuator.hasPendingFeed("test"));
        operationState = "reconciled";
        actuator.clearResolvedFeed("test", 7);
        assertFalse(actuator.hasPendingFeed("test"));
    }

    @Test
    void changedSlotOrEndpointCannotReplayPendingRequest() throws Exception {
        loseResponse.set(true);
        assertThrows(IOException.class, () -> actuator.feed("test", 7, actuator.getAssignment(7), false));
        assertThrows(IOException.class, () -> actuator.feed("test", 8, null, false));
        actuator.setAddress("localhost");
        assertThrows(IOException.class, () -> actuator.feed("test", 7, null, false));
        assertEquals(1, requests.size());
    }

    @Test
    void executingJobProvidesItsNameAndMachineCanResolveByName() throws Exception {
        actuator.setMachineId("");
        actuator.setMachineName("Workbench");
        Job job = new Job();
        job.setFile(directory.resolve("Board run.job.xml").toFile());
        actuator.prepareJob(job);
        assertEquals("Board run", actuator.getJobName());
        assertEquals("running-job", actuator.getJobId());
        assertEquals("machine", actuator.resolveMachineId());
        duplicateMachine = true;
        assertThrows(IOException.class, actuator::resolveMachineId);
        assertThrows(IOException.class, () -> actuator.prepareJob(new Job()));
    }

    @Test
    void feederTabFeedAdvancesInReverseModeWithoutAnyJobAndEachNewAttemptConsumesOnce() throws Exception {
        addPart();
        VisionlessFeeder feeder = new VisionlessFeeder();
        feeder.setInventoryActuator(actuator);
        feeder.setSlot(7);
        feeder.setFeedAfterPick(true);
        machine.addFeeder(feeder);
        feeder.syncComponent();
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getHead()).thenReturn(mock(Head.class));
        feeder.feedForTest(nozzle);
        feeder.feedForTest(nozzle);
        assertEquals(2, requests.size());
        assertNotEquals(operationIds.get(0), operationIds.get(1));
        assertFalse(requests.get(0).getAsJsonObject("parameters").has("jobId"));
        feeder.feed(nozzle);
        assertEquals(2, requests.size());
        feeder.postPick(nozzle);
        assertEquals(3, requests.size());
    }

    @Test
    void changedBackendComponentStopsBeforeFeeding() throws Exception {
        addPart();
        VisionlessFeeder feeder = new VisionlessFeeder();
        feeder.setInventoryActuator(actuator);
        feeder.setSlot(7);
        machine.addFeeder(feeder);
        feeder.syncComponent();
        assignedPart = "replacement-part";
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getHead()).thenReturn(mock(Head.class));
        assertThrows(IOException.class, () -> feeder.feed(nozzle));
        assertTrue(requests.isEmpty());
    }
}
