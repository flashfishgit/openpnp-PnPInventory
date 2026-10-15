package org.openpnp.machine.pnpinventory;

import java.io.IOException;
import org.pmw.tinylog.Logger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.time.Instant;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Shared REST connection, assignment lookup and durable, idempotent feed operations. */
public class PnpInventoryActuator extends ReferenceActuator {
    @Attribute(required = false)
    private String address = "127.0.0.1";
    @Attribute(required = false)
    private int port = 8080;
    @Element(required = false)
    private String accessToken = "";
    @Attribute(required = false)
    private String busId = "";
    @Attribute(required = false)
    private String machineId = "";
    @Attribute(required = false)
    private String machineName = "";
    @Attribute(required = false)
    private int requestTimeoutMilliseconds = 5000;
    // Read older machine.xml files; completion timing now belongs to each slot feeder.
    @Attribute(name = "operationTimeoutMilliseconds", required = false)
    private Integer legacyOperationTimeoutMilliseconds;
    @Attribute(required = false)
    private int pollIntervalMilliseconds = 200;
    @Attribute(required = false)
    private String username = "";
    @Attribute(required = false)
    private String sessionId = "";
    @Attribute(required = false)
    private String sessionExpiresAt = "";
    @Attribute(required = false)
    private String jobName = "";
    // Persist identity, but revalidate state and fetch the allocation for every new job feed.
    @Attribute(required = false)
    private String jobId = "";
    private transient long allocationRevision = 1;

    public PnpInventoryActuator() {
        setValueType(ActuatorValueType.Double);
    }

    public String getAddress() { return address; }
    public void setAddress(String value) {
        if (!address.equals(value.trim())) {
            clearConnectionContext();
        }
        address = value.trim();
    }
    public int getPort() { return port; }
    public void setPort(int value) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        if (port != value) {
            clearConnectionContext();
        }
        port = value;
    }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) {
        if (!accessToken.equals(value.trim())) {
            sessionId = "";
            sessionExpiresAt = "";
        }
        String old = accessToken;
        accessToken = value.trim();
        firePropertyChange("accessToken", old, accessToken);
    }
    public String getBusId() { return busId; }
    public void setBusId(String value) { busId = value.trim(); }
    public String getMachineId() { return machineId; }
    public void setMachineId(String value) {
        if (!machineId.equals(value.trim())) {
            setJobId("");
        }
        machineId = value.trim();
    }
    public String getMachineName() { return machineName; }
    public void setMachineName(String value) {
        if (!machineName.equals(value.trim())) {
            setJobId("");
        }
        machineName = value.trim();
    }

    public static final class MachineChoice {
        public final String id;
        public final String name;
        MachineChoice(JsonObject value) throws IOException {
            id = PnpInventoryApi.required(value, "id");
            name = PnpInventoryApi.required(value, "name");
        }
        @Override public String toString() { return name + " (" + id + ")"; }
    }

    /** Read-only discovery, also available with the machine disabled. */
    private java.util.List<JsonObject> collection(String path) throws Exception {
        PnpInventoryApi connection = api();
        String first = path;
        java.util.List<JsonObject> result = new java.util.ArrayList<>();
        Set<String> cursors = new HashSet<>();
        while (true) {
            JsonObject page = connection.request("GET", path, null);
            for (JsonElement value : PnpInventoryApi.items(page)) {
                result.add(PnpInventoryApi.object(value, "items entry"));
            }
            if (!page.has("nextCursor") || page.get("nextCursor").isJsonNull()) {
                return result;
            }
            String cursor = PnpInventoryApi.required(page, "nextCursor");
            if (!cursors.add(cursor)) {
                throw new IOException("Repeated discovery cursor.");
            }
            path = first + "&cursor=" + PnpInventoryApi.segment(cursor);
        }
    }

    public java.util.List<MachineChoice> discoverMachines() throws Exception {
        java.util.List<MachineChoice> result = new java.util.ArrayList<>();
        for (JsonObject value : collection("machines?limit=200")) {
            result.add(new MachineChoice(value));
        }
        return result;
    }

    public String testConnection() throws Exception {
        // Health is intentionally usable before authentication or with an expired session.
        JsonObject health = new PnpInventoryApi(address, port, "", requestTimeoutMilliseconds)
                .request("GET", "health", null);
        String version = PnpInventoryApi.required(health, "apiVersion");
        if (!"v1".equals(version)) {
            throw new IOException("Unsupported backend API version. This integration requires v1.");
        }
        String state = PnpInventoryApi.required(health, "status");
        String database = PnpInventoryApi.required(health, "database");
        String fbus = PnpInventoryApi.required(health, "fbus");
        if (!("ok".equals(state) || "degraded".equals(state))
                || !("connected".equals(database) || "unavailable".equals(database))
                || !("connected".equals(fbus) || "unavailable".equals(fbus))) {
            throw PnpInventoryApi.invalid("health");
        }
        return "Connected; API v1 supported; service " + state + "; database " + database + "; bus " + fbus;
    }

    public String authenticationStatus() {
        if (accessToken.isEmpty()) { return "Not logged in"; }
        if (sessionExpiresAt.isEmpty()) { return "Manual token configured; expiry unknown"; }
        try {
            return (parseExpiry(sessionExpiresAt).isAfter(Instant.now()) ? "Logged in; expires " : "Login expired: ")
                    + sessionExpiresAt;
        }
        catch (IOException e) { return "Invalid session expiry; log in again"; }
    }

    /** Publish OpenPnP-owned definitions; the backend atomically creates or updates mapped records. */
    public synchronized String syncCatalog() throws Exception {
        try {
            return new PnpInventoryCatalogSync(this).sync();
        }
        catch (Exception e) {
            Logger.warn("PnPInventory catalog sync stopped ({}); retry resumes any saved request", e.getClass().getSimpleName());
            throw e;
        }
    }

    /** Scan first; update configured physical slot placeholders only after all reads validate. */
    public synchronized String syncAllSlots() throws Exception {
        java.util.List<PnpInventorySlotFeeder> slots = new java.util.ArrayList<>();
        for (org.openpnp.spi.Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder instanceof PnpInventorySlotFeeder
                    && ((PnpInventorySlotFeeder) feeder).getInventoryActuator() == this) {
                PnpInventorySlotFeeder slot = (PnpInventorySlotFeeder) feeder;
                slot.checkSyncAllowed(this);
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) { throw new IOException("No slot feeders use this actuator. Configure the physical slots first."); }
        String scanBus = busId;
        if (scanBus.isEmpty()) {
            Set<String> buses = new HashSet<>();
            for (JsonObject value : collection("feeders?limit=200")) {
                if ("fbus".equals(PnpInventoryApi.required(value, "kind"))
                        && value.has("busId") && !value.get("busId").isJsonNull()) {
                    buses.add(PnpInventoryApi.required(value, "busId"));
                }
            }
            if (buses.size() != 1) { throw new IOException("Set the actuator Bus UUID before scanning; a unique bus could not be determined."); }
            scanBus = buses.iterator().next();
        }
        PnpInventoryApi connection = api();
        JsonObject result = scanBus(connection, scanBus);
        JsonElement discovered = result.get("discoveredFeederIds");
        JsonElement offline = result.get("offlineFeederIds");
        if (discovered == null || !discovered.isJsonArray() || offline == null || !offline.isJsonArray()) {
            throw PnpInventoryApi.invalid("scan result");
        }
        java.util.Map<Integer, JsonObject> detected = new java.util.HashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement value : discovered.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isEmpty() || !ids.add(value.getAsString())) {
                throw PnpInventoryApi.invalid("discoveredFeederIds");
            }
            JsonObject device = connection.request("GET", "feeders/" + PnpInventoryApi.segment(value.getAsString()), null);
            if (!value.getAsString().equals(PnpInventoryApi.required(device, "id"))
                    || !scanBus.equals(PnpInventoryApi.required(device, "busId"))
                    || !"fbus".equals(PnpInventoryApi.required(device, "kind"))
                    || !PnpInventoryApi.bool(device, "online")) {
                throw new IOException("Scan observations changed or are inconsistent. Retry Sync all.");
            }
            int slot = Math.toIntExact(PnpInventoryApi.integer(device, "fbusAddress"));
            validateSlot(slot);
            if (detected.put(slot, device) != null) { throw new IOException("Multiple detected feeders occupy slot " + slot); }
        }
        Set<String> offlineIds = new HashSet<>();
        for (JsonElement value : offline.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isEmpty() || ids.contains(value.getAsString())
                    || !offlineIds.add(value.getAsString())) {
                throw PnpInventoryApi.invalid("offlineFeederIds");
            }
        }
        java.util.List<PnpInventorySlotFeeder.ComponentSync> updates = new java.util.ArrayList<>();
        java.util.List<PnpInventorySlotFeeder> empty = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (PnpInventorySlotFeeder slot : slots) {
            try {
                JsonObject device = detected.get(slot.getSlot());
                if (device == null) {
                    empty.add(slot);
                    continue;
                }
                String id = PnpInventoryApi.required(device, "id");
                JsonObject assignment;
                try { assignment = connection.request("GET", "feeders/" + PnpInventoryApi.segment(id) + "/assignment", null); }
                catch (PnpInventoryApi.HttpFailure e) {
                    if (e.status != 404) { throw e; }
                    empty.add(slot);
                    continue;
                }
                JsonObject container = connection.request("GET", "containers/"
                        + PnpInventoryApi.segment(PnpInventoryApi.required(assignment, "containerId")), null);
                updates.add(slot.previewAssignment(this, new Assignment(id, assignment, container)));
            }
            catch (Exception e) { failures.add(slot.getName() + ": " + e.getMessage()); }
        }
        if (!failures.isEmpty()) { throw new IOException(String.join("\n", failures)); }
        for (PnpInventorySlotFeeder slot : slots) { slot.checkSyncAllowed(this); }
        // The machine queue serialises this operation with feeding; validate all updates before clearing empties.
        PnpInventorySlotFeeder.applySync(updates);
        for (PnpInventorySlotFeeder slot : empty) { slot.clearSlotComponent(this); }
        Logger.info("PnPInventory slot sync completed: {} assigned, {} empty", updates.size(), empty.size());
        return "Synced " + updates.size() + " slots; cleared " + empty.size() + " empty slots.";
    }

    private JsonObject scanBus(PnpInventoryApi connection, String scanBus) throws Exception {
        Path file = journal("bus-scan");
        JsonObject entry;
        if (Files.exists(file)) {
            entry = readIntent(file);
            if (!connection.getBaseAddress().equals(PnpInventoryApi.required(entry, "endpoint"))
                    || !scanBus.equals(PnpInventoryApi.required(entry, "busId"))) {
                throw new IOException("Restore the original endpoint and bus to finish the pending scan.");
            }
        }
        else {
            entry = new JsonObject();
            entry.addProperty("id", UUID.randomUUID().toString());
            entry.addProperty("endpoint", connection.getBaseAddress());
            entry.addProperty("busId", scanBus);
            persistIntent(file, entry);
        }
        String id = PnpInventoryApi.required(entry, "id");
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("type", "bus.scan");
        request.addProperty("busId", scanBus);
        request.add("parameters", new JsonObject());
        JsonObject operation = connection.request("POST", "operations", request);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            if (!id.equals(PnpInventoryApi.required(operation, "id"))) { throw new IOException("Scan operation ID mismatch."); }
            String state = PnpInventoryApi.required(operation, "state");
            if ("succeeded".equals(state)) {
                JsonObject result = PnpInventoryApi.object(operation.get("result"), "scan result");
                Files.delete(file);
                return result;
            }
            if (java.util.Arrays.asList("failed", "cancelled", "reconciled").contains(state)) {
                Files.delete(file);
                throw new IOException("Bus scan did not complete. Slot components were not changed. Retry Sync all.");
            }
            if (!java.util.Arrays.asList("queued", "dispatched", "running").contains(state)) {
                throw new IOException("Bus scan outcome is uncertain. Resolve it in the backend and retry Sync all.");
            }
            if (System.nanoTime() >= deadline) { throw new IOException("Bus scan timed out. Retry Sync all to resume the saved scan."); }
            try { Thread.sleep(pollIntervalMilliseconds); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            operation = connection.request("GET", "operations/" + PnpInventoryApi.segment(id), null);
        }
    }

    public static final class SlotChoice {
        public final int slot;
        public final String description;
        SlotChoice(int slot, String description) {
            this.slot = slot;
            this.description = description;
        }
        @Override public String toString() { return "Slot " + slot + " — " + description; }
    }

    public java.util.List<SlotChoice> discoverSlots() throws Exception {
        java.util.List<SlotChoice> result = new java.util.ArrayList<>();
        for (JsonObject value : collection("feeders?limit=200" + (busId.isEmpty() ? "" : "&busId=" + PnpInventoryApi.segment(busId)))) {
            if (!"fbus".equals(PnpInventoryApi.required(value, "kind"))) { continue; }
            int slot = Math.toIntExact(PnpInventoryApi.integer(value, "fbusAddress"));
            validateSlot(slot);
            String description = (PnpInventoryApi.bool(value, "online") ? "online" : "offline")
                    + (PnpInventoryApi.bool(value, "enabled") ? "" : ", disabled");
            try {
                JsonObject assignment = api().request("GET", "feeders/"
                        + PnpInventoryApi.segment(PnpInventoryApi.required(value, "id")) + "/assignment", null);
                String container = PnpInventoryApi.required(assignment, "containerId");
                JsonObject contents = api().request("GET", "containers/" + PnpInventoryApi.segment(container), null);
                description += "; part " + PnpInventoryApi.required(contents, "partId") + "; container " + container;
            }
            catch (IOException e) {
                description += "; assignment unavailable (use Sync component for details)";
            }
            result.add(new SlotChoice(slot, description));
        }
        return result;
    }

    /** Inspect the existing operation without creating or replaying anything. */
    public synchronized String pendingFeedState(String caller, int slot) throws Exception {
        Path file = journal(caller);
        if (!Files.exists(file)) { return "none"; }
        JsonObject entry = readIntent(file);
        PnpInventoryApi connection = api();
        if (!connection.getBaseAddress().equals(PnpInventoryApi.required(entry, "endpoint"))
                || slot != PnpInventoryApi.integer(entry, "slot")) {
            throw new IOException("Restore the saved feed's original endpoint and slot before recovery.");
        }
        String id = PnpInventoryApi.required(entry, "id");
        JsonObject operation = connection.request("GET", "operations/" + PnpInventoryApi.segment(id), null);
        if (!id.equals(PnpInventoryApi.required(operation, "id"))) {
            throw new IOException("Backend returned a different operation ID.");
        }
        String state = PnpInventoryApi.required(operation, "state");
        return java.util.Arrays.asList("queued", "dispatched", "running", "succeeded", "failed", "cancelled",
                "reconciled", "uncertain").contains(state) ? state : "unknown";
    }

    public synchronized String resolveMachineId() throws Exception {
        if (machineName.isEmpty()) {
            return machineId;
        }
        String path = "machines?name=" + PnpInventoryApi.segment(machineName) + "&limit=200";
        String first = path;
        String found = null;
        Set<String> cursors = new HashSet<>();
        do {
            JsonObject page = api().request("GET", path, null);
            for (JsonElement element : PnpInventoryApi.items(page)) {
                JsonObject machine = PnpInventoryApi.object(element, "items entry");
                if (machineName.equals(PnpInventoryApi.required(machine, "name"))) {
                    if (found != null) {
                        throw new IOException("More than one backend machine has the name '" + machineName + "'. Use a unique name or a UUID.");
                    }
                    found = PnpInventoryApi.required(machine, "id");
                }
            }
            JsonElement cursor = page.get("nextCursor");
            if (cursor == null || cursor.isJsonNull()) {
                break;
            }
            if (!cursors.add(PnpInventoryApi.required(page, "nextCursor"))) {
                throw new IOException("Repeated machine lookup cursor.");
            }
            path = first + "&cursor=" + PnpInventoryApi.segment(PnpInventoryApi.required(page, "nextCursor"));
        } while (true);
        if (found == null) {
            throw new IOException("No backend machine named '" + machineName + "'.");
        }
        if (!machineId.isEmpty() && !machineId.equals(found)) {
            throw new IOException("Machine name and configured UUID refer to different machines. Configure one identity or matching values.");
        }
        return found;
    }

    public synchronized void resetJobContext() {
        setJobId("");
    }

    /** Job context comes from the executing processor, never from a manual actuator field. */
    public synchronized void prepareJob(Job job) throws Exception {
        if (job == null || job.getFile() == null) {
            throw new IOException("Save the OpenPnP job before feeding so it has a stable backend job name.");
        }
        String actualName = job.getFile().getName().replaceFirst("(?i)(\\.job)?\\.xml$", "");
        if (actualName.isEmpty()) {
            throw new IOException("The OpenPnP job filename has no name.");
        }
        setJobName(actualName);
        if (jobId.isEmpty()) {
            selectJobByName();
        }
        else {
            refreshJobContext();
        }
    }

    public String getJobId() { return jobId; }
    public void setJobId(String value) {
        String old = jobId;
        jobId = value.trim();
        firePropertyChange("jobId", old, jobId);
    }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value.trim(); }
    public String getSessionExpiresAt() { return sessionExpiresAt; }
    public String getJobName() { return jobName; }
    public void setJobName(String value) {
        if (!jobName.equals(value.trim())) {
            setJobId("");
        }
        jobName = value.trim();
    }

    private void clearConnectionContext() {
        setAccessToken("");
        setJobId("");
    }

    /** Password is used once and never retained or serialized. No automatic login retries. */
    public synchronized void login(String password) throws Exception {
        JsonObject credentials = new JsonObject();
        credentials.addProperty("username", username);
        credentials.addProperty("password", password);
        JsonObject session = new PnpInventoryApi(address, port, "", requestTimeoutMilliseconds)
                .request("POST", "sessions", credentials);
        String id = PnpInventoryApi.required(session, "id");
        String expiresAt = PnpInventoryApi.required(session, "expiresAt");
        String token = PnpInventoryApi.required(session, "accessToken");
        if (!parseExpiry(expiresAt).isAfter(Instant.now()) || PnpInventoryApi.bool(session, "revoked")) {
            throw new IOException("The backend returned an expired or revoked session.");
        }
        setAccessToken(token);
        sessionId = id;
        sessionExpiresAt = expiresAt;
        Logger.info("PnPInventory login succeeded");
    }

    public synchronized void logout() throws Exception {
        if (!sessionId.isEmpty()) {
            // Allow revocation even when our cached expiry time has passed.
            new PnpInventoryApi(address, port, accessToken, requestTimeoutMilliseconds)
                    .request("DELETE", "sessions/" + PnpInventoryApi.segment(sessionId), null);
        }
        setAccessToken("");
        setJobId("");
    }

    /** The draft provides job discovery through snapshots, not an invented GET /jobs endpoint. */
    public synchronized void selectJobByName() throws Exception {
        String resolvedMachineId = resolveMachineId();
        if (jobName.isEmpty() || resolvedMachineId.isEmpty()) {
            throw new IOException("Configure a backend machine name or UUID and use a saved OpenPnP job.");
        }
        setJobId("");
        PnpInventoryApi api = api();
        JsonObject body = new JsonObject();
        JsonArray collections = new JsonArray();
        collections.add(new com.google.gson.JsonPrimitive("jobs"));
        body.add("collections", collections);
        String snapshotId = UUID.randomUUID().toString();
        body.addProperty("id", snapshotId);
        // Store the immutable snapshot request before transmission, just like a feed intent.
        Path intent = journal("job-lookup");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("id", snapshotId);
        snapshot.add("request", body);
        persistIntent(intent, snapshot);
        api.request("POST", "sync-snapshots", body);
        String base = "sync-snapshots/" + snapshotId + "/items?limit=200";
        String path = base;
        String selectedId = null;
        Set<String> cursors = new HashSet<>();
        do {
            JsonObject page = api.request("GET", path, null);
            for (JsonElement item : PnpInventoryApi.items(page)) {
                JsonObject row = PnpInventoryApi.object(item, "items entry");
                JsonObject job = PnpInventoryApi.object(row.get("data"), "data");
                if ("jobs".equals(PnpInventoryApi.required(row, "collection"))
                        && jobName.equals(PnpInventoryApi.required(job, "name"))
                        && resolvedMachineId.equals(PnpInventoryApi.required(job, "machineId"))
                        && "running".equals(PnpInventoryApi.required(job, "state"))) {
                    if (selectedId != null) {
                        throw new IOException("More than one running job has this name on this machine. Use unique job names.");
                    }
                    selectedId = PnpInventoryApi.required(row, "resourceId");
                }
            }
            JsonElement cursor = page.get("nextCursor");
            if (cursor == null || cursor.isJsonNull()) {
                break;
            }
            if (!cursors.add(PnpInventoryApi.required(page, "nextCursor"))) {
                throw new IOException("Repeated job snapshot cursor.");
            }
            path = base + "&cursor=" + PnpInventoryApi.segment(PnpInventoryApi.required(page, "nextCursor"));
        } while (true);
        if (selectedId == null) {
            throw new IOException("No running backend job named '" + jobName + "' exists on this machine.");
        }
        long revision = readJobAllocation(api, selectedId);
        setJobId(selectedId);
        setAllocationRevision(revision);
        Files.deleteIfExists(intent);
        Logger.info("PnPInventory executing job matched to backend; allocation revision {}", revision);
    }

    public synchronized void refreshJobContext() throws Exception {
        if (jobId.isEmpty()) {
            throw new IOException("The executing OpenPnP job has not been matched to a running backend job.");
        }
        setAllocationRevision(readJobAllocation(api(), jobId));
    }

    private long readJobAllocation(PnpInventoryApi api, String id) throws Exception {
        String resolvedMachineId = resolveMachineId();
        JsonObject job = api.request("GET", "jobs/" + PnpInventoryApi.segment(id), null);
        if (!id.equals(PnpInventoryApi.required(job, "id"))
                || !"running".equals(PnpInventoryApi.required(job, "state"))
                || (!resolvedMachineId.isEmpty() && !resolvedMachineId.equals(PnpInventoryApi.required(job, "machineId")))
                || (!jobName.isEmpty() && !jobName.equals(PnpInventoryApi.required(job, "name")))) {
            throw new IOException("The selected backend job is not running or no longer matches this machine/name.");
        }
        JsonObject allocation = api.request("GET", "jobs/" + PnpInventoryApi.segment(id) + "/allocations", null);
        if (!id.equals(PnpInventoryApi.required(allocation, "jobId"))) {
            throw new IOException("The backend returned an allocation for another job.");
        }
        long revision = PnpInventoryApi.integer(allocation, "revision");
        if (revision <= 0) {
            throw PnpInventoryApi.invalid("revision");
        }
        return revision;
    }
    public long getAllocationRevision() { return allocationRevision; }
    public void setAllocationRevision(long value) {
        positive(value);
        long old = allocationRevision;
        allocationRevision = value;
        firePropertyChange("allocationRevision", old, value);
    }
    public int getRequestTimeoutMilliseconds() { return requestTimeoutMilliseconds; }
    public void setRequestTimeoutMilliseconds(int value) {
        positive(value);
        requestTimeoutMilliseconds = value;
    }
    public int getPollIntervalMilliseconds() { return pollIntervalMilliseconds; }
    public void setPollIntervalMilliseconds(int value) {
        positive(value);
        pollIntervalMilliseconds = value;
    }
    private static void positive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Value must be greater than zero.");
        }
    }

    private static Instant parseExpiry(String value) throws IOException {
        try {
            return Instant.parse(value);
        }
        catch (java.time.format.DateTimeParseException e) {
            throw PnpInventoryApi.invalid("expiresAt");
        }
    }

    PnpInventoryApi api() throws Exception {
        if (!sessionExpiresAt.isEmpty() && !parseExpiry(sessionExpiresAt).isAfter(Instant.now())) {
            throw new IOException("PnPInventory login expired. Use Log in in the actuator settings.");
        }
        return new PnpInventoryApi(address, port, accessToken, requestTimeoutMilliseconds);
    }

    public static class Assignment {
        public final String feederId;
        public final String containerId;
        public final String partId;
        public final long revision;
        public final double pitchMm;

        Assignment(String feederId, JsonObject assignment, JsonObject container) throws Exception {
            this.feederId = feederId;
            containerId = PnpInventoryApi.required(assignment, "containerId");
            partId = PnpInventoryApi.required(container, "partId");
            revision = PnpInventoryApi.integer(assignment, "revision");
            pitchMm = PnpInventoryApi.number(assignment, "feedPitchMm");
            if (!"confirmed".equals(PnpInventoryApi.required(assignment, "state"))
                    || !assignment.has("appliedRevision") || assignment.get("appliedRevision").isJsonNull()
                    || PnpInventoryApi.integer(assignment, "appliedRevision") != revision
                    || !"active".equals(PnpInventoryApi.required(container, "status"))
                    || revision <= 0 || !Double.isFinite(pitchMm) || pitchMm <= 0) {
                throw new IOException("Slot needs an active container and a confirmed assignment with positive pitch.");
            }
        }
    }

    public Assignment getAssignment(int slot) throws Exception {
        validateSlot(slot);
        PnpInventoryApi api = api();
        String id = PnpInventoryApi.required(api.findSlot(slot, busId), "id");
        JsonObject assignment = api.request("GET", "feeders/" + PnpInventoryApi.segment(id) + "/assignment", null);
        JsonObject container = api.request("GET", "containers/"
                + PnpInventoryApi.segment(PnpInventoryApi.required(assignment, "containerId")), null);
        return new Assignment(id, assignment, container);
    }

    static void validateSlot(int slot) {
        if (slot < 0 || slot > 254) {
            throw new IllegalArgumentException("Slot (F-Bus address) must be between 0 and 254.");
        }
    }

    /** Stable file per caller, persisted before transmission, retained across timeout and restart. */
    Path journal(String caller) throws IOException {
        if (Configuration.get().getConfigurationDirectory() == null) {
            throw new IOException("A configuration directory is required for durable feed requests.");
        }
        Path directory = Configuration.get().getConfigurationDirectory().toPath().resolve("pnpinventory-operations");
        Files.createDirectories(directory);
        return directory.resolve(UUID.nameUUIDFromBytes((getId() + ":" + caller)
                .getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    public synchronized boolean hasPendingFeed(String caller) throws Exception {
        return Files.exists(journal(caller));
    }

    /** Retries replay the immutable journal, including its original assignment and job context. */
    public synchronized void feed(String caller, int slot, Assignment assignment, boolean jobFeed) throws Exception {
        feed(caller, slot, assignment, jobFeed, 30000);
    }

    public synchronized void feed(String caller, int slot, Assignment assignment, boolean jobFeed,
            int completionTimeoutMilliseconds) throws Exception {
        Logger.info("PnPInventory feed requested for slot {} (job feed: {})", slot, jobFeed);
        try {
            performFeed(caller, slot, assignment, jobFeed, completionTimeoutMilliseconds);
            Logger.info("PnPInventory feed completed for slot {}; awaiting local acknowledgement", slot);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("PnPInventory feed interrupted for slot {}; any saved request is retained", slot);
            throw e;
        }
        catch (Exception e) {
            Logger.warn("PnPInventory feed stopped for slot {} ({}); any saved request is retained for recovery",
                    slot, e.getClass().getSimpleName());
            throw e;
        }
    }

    private void performFeed(String caller, int slot, Assignment assignment, boolean jobFeed,
            int completionTimeoutMilliseconds) throws Exception {
        positive(completionTimeoutMilliseconds);
        if (Files.exists(journal("bus-scan"))) {
            throw new IOException("A bus scan is pending. Use Sync all slots in the actuator to finish it before feeding.");
        }
        Path file = journal(caller);
        PnpInventoryApi api = api();
        JsonObject entry;
        if (Files.exists(file)) {
            entry = readIntent(file);
            Logger.info("PnPInventory replaying saved feed for slot {}", slot);
            if (!api.getBaseAddress().equals(PnpInventoryApi.required(entry, "endpoint"))
                    || slot != PnpInventoryApi.integer(entry, "slot")) {
                throw new IOException("A pending feed belongs to a different endpoint or slot. Restore the original configuration.");
            }
        }
        else {
            if (jobFeed && jobId.isEmpty()) {
                throw new IOException("No backend job matches the executing OpenPnP job. Check its name and backend running state.");
            }
            if (jobFeed) {
                setAllocationRevision(readJobAllocation(api, jobId));
            }
            JsonObject parameters = new JsonObject();
            parameters.addProperty("containerId", assignment.containerId);
            parameters.addProperty("expectedAssignmentRevision", assignment.revision);
            parameters.addProperty("quantity", 1);
            if (jobFeed) {
                parameters.addProperty("jobId", jobId);
                parameters.addProperty("cycleId", UUID.randomUUID().toString());
                parameters.addProperty("expectedAllocationRevision", allocationRevision);
            }
            JsonObject body = new JsonObject();
            body.addProperty("type", "feeder.feed");
            body.addProperty("feederId", assignment.feederId);
            body.add("parameters", parameters);
            entry = new JsonObject();
            entry.addProperty("id", UUID.randomUUID().toString());
            entry.addProperty("endpoint", api.getBaseAddress());
            entry.addProperty("slot", slot);
            entry.add("request", body);
            persistIntent(file, entry);
        }
        String id = PnpInventoryApi.required(entry, "id");
        String path = "operations/" + PnpInventoryApi.segment(id);
        long deadline = System.nanoTime() + completionTimeoutMilliseconds * 1_000_000L;
        // Immutable POST replay uses the same client-chosen ID, including for legacy saved intents.
        JsonObject request = new JsonParser().parse(PnpInventoryApi.object(entry.get("request"), "saved request").toString()).getAsJsonObject();
        request.addProperty("id", id);
        JsonObject operation = api.request("POST", "operations", request);
        String previousState = null;
        while (true) {
            if (!id.equals(PnpInventoryApi.required(operation, "id"))) {
                throw new IOException("PnPInventory returned a different operation ID.");
            }
            String state = PnpInventoryApi.required(operation, "state");
            if (!state.equals(previousState)) {
                // Only known states are logged; untrusted backend strings are never logged.
                Logger.info("PnPInventory slot {} operation {} state {}", slot,
                        id.replaceAll("[^a-zA-Z0-9-]", "_").substring(0, Math.min(id.length(), 64)),
                        java.util.Arrays.asList("queued", "dispatched", "running", "succeeded", "failed",
                                "cancelled", "reconciled").contains(state) ? state : "unknown");
                previousState = state;
            }
            if ("succeeded".equals(state)) {
                return;
            }
            if (!("queued".equals(state) || "dispatched".equals(state) || "running".equals(state))) {
                throw new IOException("PnPInventory operation " + id + " stopped or returned an unsupported state"
                        + ". Resolve it in the backend; automatic feeding has stopped.");
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("Timed out waiting for PnPInventory operation " + id
                        + ". Retry reuses this operation; it does not request another feed.");
            }
            Thread.sleep(pollIntervalMilliseconds);
            operation = api.request("GET", path, null);
        }
    }

    JsonObject readIntent(Path file) throws IOException {
        try {
            return PnpInventoryApi.object(new JsonParser().parse(Files.readString(file)), "saved feed");
        }
        catch (RuntimeException e) {
            Logger.warn("PnPInventory saved feed is invalid; it has been preserved for recovery");
            throw new IOException("Saved PnPInventory feed is invalid. Restore the journal from backup or reconcile with the backend before feeding.");
        }
    }

    void persistIntent(Path file, JsonObject entry) throws Exception {
        Path temporary = Files.createTempFile(file.getParent(), "operation-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(entry.toString());
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void clearResolvedFeed(String caller, int slot) throws Exception {
        Path file = journal(caller);
        if (!Files.exists(file)) {
            throw new IOException("There is no saved feed request.");
        }
        JsonObject entry = readIntent(file);
        PnpInventoryApi api = api();
        if (!api.getBaseAddress().equals(PnpInventoryApi.required(entry, "endpoint"))
                || slot != PnpInventoryApi.integer(entry, "slot")) {
            throw new IOException("Restore the original endpoint and slot before resolving this request.");
        }
        String id = PnpInventoryApi.required(entry, "id");
        JsonObject operation = api.request("GET", "operations/" + PnpInventoryApi.segment(id), null);
        String state = PnpInventoryApi.required(operation, "state");
        if (!id.equals(PnpInventoryApi.required(operation, "id"))
                || !("failed".equals(state) || "cancelled".equals(state) || "reconciled".equals(state))) {
            throw new IOException("Only a failed, cancelled or backend-reconciled operation can be cleared. Current state: " + state);
        }
        Files.delete(file);
        Logger.info("PnPInventory resolved feed cleared for slot {}", slot);
    }

    synchronized void acknowledgeFeed(String caller) throws Exception {
        Files.deleteIfExists(journal(caller));
    }

    @Override
    public boolean isDriverless() { return true; }

    @Override
    protected void driveActuation(double value) throws Exception {
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new IllegalArgumentException("Actuator value must be an integer slot number.");
        }
        validateSlot((int) value);
        String caller = "manual-slot-" + (int) value;
        Assignment assignment = hasPendingFeed(caller) ? null : getAssignment((int) value);
        feed(caller, (int) value, assignment, false);
        acknowledgeFeed(caller);
    }

    @Override
    protected void driveActuation(String value) throws Exception { driveActuation(Double.parseDouble(value)); }

    @Override
    protected void driveActuation(boolean value) throws Exception {
        throw new IllegalArgumentException("Use a numeric slot number, not a Boolean actuation.");
    }

    @Override
    public Wizard getConfigurationWizard() { return new PnpInventoryActuatorConfigurationWizard(this); }
}
