package org.openpnp.machine.pnpinventory;

import java.io.IOException;
import java.util.Objects;

import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Job;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.simpleframework.xml.Attribute;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One API operation per component. The backend owns physical pitch and stock accounting. */
public class PnpInventorySlotFeeder extends PnpInventoryFeederAutoVision {
    @Attribute(required = false)
    private int slot = 0;
    @Attribute(required = false)
    private String inventoryActuatorId;
    @Attribute(required = false)
    private String backendPartId;
    @Attribute(required = false)
    private String syncedPartId;
    @Attribute(required = false)
    private double rowSpacingMm = 16;
    @Attribute(required = false)
    private int feedCompletionTimeoutMilliseconds = 30000;
    private transient boolean manualFeedOnly;
    private transient boolean jobFeed;
    private transient boolean deferredPick;
    private transient boolean deferredJobFeed;
    private transient PnpInventoryActuator.Assignment deferredAssignment;

    public int getFeedCompletionTimeoutMilliseconds() { return feedCompletionTimeoutMilliseconds; }
    public void setFeedCompletionTimeoutMilliseconds(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Feed completion timeout must be positive.");
        }
        feedCompletionTimeoutMilliseconds = value;
    }

    public int getSlot() { return slot; }
    public void setSlot(int value) {
        PnpInventoryActuator.validateSlot(value);
        if (slot != value) {
            checkReconfiguration();
        }
        slot = value;
    }
    public double getRowSpacingMm() { return rowSpacingMm; }
    public void setRowSpacingMm(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Row spacing must be positive.");
        }
        rowSpacingMm = value;
    }
    public PnpInventoryActuator getInventoryActuator() {
        Actuator actuator = Configuration.get().getMachine().getActuator(inventoryActuatorId);
        return actuator instanceof PnpInventoryActuator ? (PnpInventoryActuator) actuator : null;
    }
    public void setInventoryActuator(PnpInventoryActuator actuator) {
        String newId = actuator == null ? null : actuator.getId();
        if (!Objects.equals(inventoryActuatorId, newId)) {
            checkReconfiguration();
        }
        inventoryActuatorId = newId;
    }

    private void checkReconfiguration() {
        try {
            PnpInventoryActuator actuator = getInventoryActuator();
            if (actuator != null && actuator.hasPendingFeed(getId())) {
                throw new IOException("Complete or resolve the pending feed before changing slot, actuator or feed order.");
            }
        }
        catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        deferredPick = false;
        deferredAssignment = null;
    }

    @Override
    public void setFeedAfterPick(boolean value) {
        if (value != isFeedAfterPick()) {
            checkReconfiguration();
        }
        super.setFeedAfterPick(value);
    }

    private PnpInventoryActuator requireActuator() throws Exception {
        PnpInventoryActuator actuator = getInventoryActuator();
        if (actuator == null) {
            throw new IOException("Select a machine-level PnpInventoryActuator for " + getName());
        }
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder != this && feeder instanceof PnpInventorySlotFeeder) {
                PnpInventorySlotFeeder other = (PnpInventorySlotFeeder) feeder;
                if (other.slot == slot && Objects.equals(other.inventoryActuatorId, inventoryActuatorId)) {
                    throw new IOException("Slot " + slot + " is also used by " + other.getName());
                }
            }
        }
        return actuator;
    }

    /** Resolve exact identities, never create parts/packages or match ambiguous display names. */
    public static final class ComponentSync {
        private final PnpInventorySlotFeeder feeder;
        private final PnpInventoryActuator actuator;
        private final int slot;
        private final PnpInventoryActuator.Assignment assignment;
        private final Part part;
        private final String packageId;
        ComponentSync(PnpInventorySlotFeeder feeder, PnpInventoryActuator actuator,
                PnpInventoryActuator.Assignment assignment, Part part, String packageId) {
            this.feeder = feeder;
            this.actuator = actuator;
            this.slot = feeder.slot;
            this.assignment = assignment;
            this.part = part;
            this.packageId = packageId;
        }
        public String describe() {
            return feeder.getName() + " / slot " + slot + ": backend " + assignment.partId
                    + " → OpenPnP " + part.getId() + ", package " + packageId
                    + ", pitch " + assignment.pitchMm + " mm";
        }
        public void validate() throws Exception {
            if (feeder.slot != slot || feeder.requireActuator() != actuator || actuator.hasPendingFeed(feeder.getId())
                    || feeder.deferredPick) {
                throw new IOException("Feeder changed or has an unfinished feed. Refresh the preview.");
            }
            ComponentSync refreshed = feeder.previewComponent();
            PnpInventoryActuator.Assignment current = refreshed.assignment;
            if (current.revision != assignment.revision || !current.containerId.equals(assignment.containerId)
                    || !current.partId.equals(assignment.partId) || current.pitchMm != assignment.pitchMm
                    || refreshed.part != part || !refreshed.packageId.equals(packageId)
                    || Configuration.get().getPart(part.getId()) != part || part.getPackage() == null
                    || !part.getPackage().getId().equals(packageId)) {
                throw new IOException("Assignment or local component changed. Refresh the preview.");
            }
        }
        private void applyValidated() {
            feeder.setPart(part);
            feeder.backendPartId = assignment.partId;
            feeder.syncedPartId = part.getId();
            feeder.setPartPitch(new Length(assignment.pitchMm, LengthUnit.Millimeters));
            feeder.setFeedPitch(new Length(assignment.pitchMm, LengthUnit.Millimeters));
            feeder.setFeedCount(0);
            feeder.resetCalibration();
        }
    }

    public static void applySync(java.util.List<ComponentSync> plans) throws Exception {
        // Validate the whole preview before changing any feeder.
        for (ComponentSync plan : plans) { plan.validate(); }
        for (ComponentSync plan : plans) { plan.applyValidated(); }
    }

    public void syncComponent() throws Exception {
        applySync(java.util.Collections.singletonList(previewComponent()));
    }

    public ComponentSync previewComponent() throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        if (deferredPick || actuator.hasPendingFeed(getId())) {
            throw new IOException("Finish the pending feed before changing this feeder's component.");
        }
        PnpInventoryActuator.Assignment assignment = actuator.getAssignment(slot);
        return previewAssignment(actuator, assignment);
    }

    ComponentSync previewAssignment(PnpInventoryActuator actuator, PnpInventoryActuator.Assignment assignment) throws Exception {
        checkSyncAllowed(actuator);
        PnpInventoryApi api = actuator.api();
        JsonObject backendPart = api.request("GET", "parts/" + PnpInventoryApi.segment(assignment.partId), null);
        String packageId = PnpInventoryApi.required(backendPart, "packageId");
        String localPartId = assignment.partId;
        String localPackageId = packageId;
        String machineId = actuator.resolveMachineId();
        if (!machineId.isEmpty()) {
            JsonObject mappings = api.request("GET", "machines/"
                    + PnpInventoryApi.segment(machineId) + "/mappings", null);
            localPartId = sourceId(mappings, "part", assignment.partId);
            localPackageId = sourceId(mappings, "package", packageId);
        }
        Part part = Configuration.get().getPart(localPartId);
        if (part == null || part.getPackage() == null
                || Configuration.get().getPackage(localPackageId) == null
                || !localPackageId.equals(part.getPackage().getId())) {
            throw new IOException("Create OpenPnP part '" + localPartId + "' with package '" + localPackageId
                    + "' first, or correct the backend machine mappings. The current component was not changed.");
        }
        return new ComponentSync(this, actuator, assignment, part, localPackageId);
    }

    void checkSyncAllowed(PnpInventoryActuator actuator) throws Exception {
        if (requireActuator() != actuator || deferredPick || actuator.hasPendingFeed(getId())) {
            throw new IOException("Finish the pending feed before synchronising " + getName());
        }
    }

    void clearSlotComponent(PnpInventoryActuator actuator) throws Exception {
        checkSyncAllowed(actuator);
        setPart(null);
        backendPartId = null;
        syncedPartId = null;
        setFeedCount(0);
        resetCalibration();
    }

    public String slotConflict(PnpInventoryActuator actuator, int candidate) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder != this && feeder instanceof PnpInventorySlotFeeder) {
                PnpInventorySlotFeeder other = (PnpInventorySlotFeeder) feeder;
                if (other.getInventoryActuator() == actuator && other.slot == candidate) {
                    return "Slot already used by " + other.getName();
                }
            }
        }
        return "";
    }

    public void preflight(Job job) throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        if (deferredPick || actuator.hasPendingFeed(getId())) {
            throw new IOException("An unfinished feed needs recovery before starting a job.");
        }
        checkedAssignment(actuator);
        actuator.prepareJob(job);
        firePropertyChange("inventoryJob", null, jobStatus());
    }

    public String recoveryState() throws Exception {
        return requireActuator().pendingFeedState(getId(), slot);
    }

    public String jobStatus() {
        PnpInventoryActuator actuator = getInventoryActuator();
        return actuator == null || actuator.getJobId().isEmpty() ? "Job: not matched yet (matched automatically at job start)"
                : "Last matched job: " + actuator.getJobName();
    }

    private String sourceId(JsonObject mappings, String kind, String id) throws Exception {
        String source = null;
        for (JsonElement item : PnpInventoryApi.items(mappings)) {
            JsonObject mapping = PnpInventoryApi.object(item, "items entry");
            if (kind.equals(PnpInventoryApi.required(mapping, "kind"))
                    && id.equals(PnpInventoryApi.required(mapping, "id"))) {
                if (source != null) {
                    throw new IOException("Ambiguous backend " + kind + " mapping for " + id);
                }
                source = PnpInventoryApi.required(mapping, "sourceId");
            }
        }
        return source == null ? id : source;
    }

    private PnpInventoryActuator.Assignment checkedAssignment(PnpInventoryActuator actuator) throws Exception {
        PnpInventoryActuator.Assignment assignment = actuator.getAssignment(slot);
        if (getPart() == null || !getPart().getId().equals(syncedPartId)
                || !assignment.partId.equals(backendPartId)) {
            throw new IOException("Slot component changed or has not been synced. Use Sync component before feeding.");
        }
        if (Math.abs(getPartPitch().convertToUnits(LengthUnit.Millimeters).getValue() - assignment.pitchMm) > 0.001) {
            throw new IOException("Backend feed pitch changed. Use Sync component before feeding.");
        }
        return assignment;
    }

    @Override
    public long getPartsPerFeedCycle() { return 1; }

    /** Called by the job processor so a job can never accidentally use the manual API contract. */
    public void feedForJob(Nozzle nozzle, Job job) throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        if (!actuator.hasPendingFeed(getId())) {
            actuator.prepareJob(job);
        }
        jobFeed = true;
        try {
            feed(nozzle);
        }
        finally {
            jobFeed = false;
        }
    }

    /** Feeders-tab Feed advances once even in pick-before-feed mode; no job is required. */
    public void feedForTest(Nozzle nozzle) throws Exception {
        if (deferredPick || (isFeedAfterPick() && requireActuator().hasPendingFeed(getId()))) {
            throw new IOException("Resume or resolve the pending post-pick feed before testing another feed.");
        }
        manualFeedOnly = true;
        try {
            feed(nozzle);
        }
        finally {
            manualFeedOnly = false;
        }
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        try { feedInternal(nozzle); }
        catch (Exception e) {
            firePropertyChange("inventoryFailure", null, e.getMessage());
            throw e;
        }
    }

    private void feedInternal(Nozzle nozzle) throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        nozzle.getHead().moveToSafeZ();
        if (isFeedAfterPick() && !manualFeedOnly) {
            if (actuator.hasPendingFeed(getId())) {
                throw new IOException("A post-pick feed is pending. Use Resume pending feed before picking another component.");
            }
            if (deferredPick) {
                throw new IOException("The previous pick has not completed its post-pick feed.");
            }
            PnpInventoryActuator.Assignment assignment = checkedAssignment(actuator);
            if (jobFeed) {
                actuator.refreshJobContext();
            }
            assertCalibrated(true);
            deferredAssignment = assignment;
            deferredPick = true;
            deferredJobFeed = jobFeed;
            return;
        }
        if (isMoveBeforeFeed()) {
            MovableUtils.moveToLocationAtSafeZ(nozzle, getPickLocation().derive(null, null, Double.NaN, null));
        }
        PnpInventoryActuator.Assignment assignment = actuator.hasPendingFeed(getId()) ? null : checkedAssignment(actuator);
        actuator.feed(getId(), slot, assignment, jobFeed, feedCompletionTimeoutMilliseconds);
        waitForFeed();
        assertCalibrated(true);
        actuator.acknowledgeFeed(getId());
        setFeedCount(getFeedCount() + 1);
    }

    @Override
    public void postPick(Nozzle nozzle) throws Exception {
        if (isFeedAfterPick() && deferredPick) {
            nozzle.getHead().moveToSafeZ();
            try { completeDeferredFeed(); }
            catch (Exception e) {
                firePropertyChange("inventoryFailure", null, e.getMessage());
                throw e;
            }
        }
    }

    /** Finish only the already prepared post-pick action; never initiate a feed from this button. */
    public void resumePendingFeed() throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        if (!isFeedAfterPick() || !actuator.hasPendingFeed(getId())) {
            throw new IOException("There is no pending post-pick feed. For feed-before-pick mode, retry the original feed.");
        }
        completeDeferredFeed();
    }

    private void completeDeferredFeed() throws Exception {
        PnpInventoryActuator actuator = requireActuator();
        PnpInventoryActuator.Assignment assignment = actuator.hasPendingFeed(getId()) ? null : deferredAssignment;
        if (assignment == null && !actuator.hasPendingFeed(getId())) {
            throw new IOException("There is no prepared post-pick feed.");
        }
        actuator.feed(getId(), slot, assignment, deferredJobFeed, feedCompletionTimeoutMilliseconds);
        waitForFeed();
        actuator.acknowledgeFeed(getId());
        deferredPick = false;
        deferredAssignment = null;
        setFeedCount(getFeedCount() + 1);
        resetCalibration();
    }

    public void clearResolvedFeed() throws Exception {
        requireActuator().clearResolvedFeed(getId(), slot);
        deferredPick = false;
        deferredAssignment = null;
        resetCalibration();
    }

    @Override
    public boolean canTakeBackPart() { return false; }

    /** Extend a known slot row, or use a perpendicular 16 mm default when this is its first feeder. */
    public PnpInventorySlotFeeder createNewInRow() throws Exception {
        requireActuator();
        PnpInventoryActuator.validateSlot(slot + 1);
        Location step = null;
        int closestSlotDistance = Integer.MAX_VALUE;
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder != this && feeder instanceof PnpInventorySlotFeeder) {
                PnpInventorySlotFeeder other = (PnpInventorySlotFeeder) feeder;
                if (Objects.equals(inventoryActuatorId, other.inventoryActuatorId)) {
                    if (other.slot == slot + 1) {
                        throw new IOException("Slot " + (slot + 1) + " already exists.");
                    }
                    int difference = slot - other.slot;
                    if (difference != 0 && Math.abs(difference) < closestSlotDistance) {
                        step = getLocation().subtract(other.getLocation()).convertToUnits(LengthUnit.Millimeters)
                                .multiply(1.0 / difference, 1.0 / difference, 0, 0);
                        closestSlotDistance = Math.abs(difference);
                    }
                }
            }
        }
        if (step == null || Math.hypot(step.getX(), step.getY()) < 0.001) {
            Location tape = getHole2Location().subtract(getHole1Location()).convertToUnits(LengthUnit.Millimeters);
            double length = Math.hypot(tape.getX(), tape.getY());
            step = length < 0.001 ? new Location(LengthUnit.Millimeters, 0, -rowSpacingMm, 0, 0)
                    : new Location(LengthUnit.Millimeters, tape.getY() / length * rowSpacingMm,
                            -tape.getX() / length * rowSpacingMm, 0, 0);
        }
        PnpInventorySlotFeeder next = new PnpInventorySlotFeeder();
        next.setInventoryActuator(getInventoryActuator());
        next.setSlot(slot + 1);
        next.setName("PnPInventory Slot " + next.getSlot());
        next.setRowSpacingMm(rowSpacingMm);
        next.setLocation(getLocation().add(step));
        next.setHole1Location(getHole1Location().add(step));
        next.setHole2Location(getHole2Location().add(step));
        next.setRotationInFeeder(getRotationInFeeder());
        next.setNormalizePickLocation(isNormalizePickLocation());
        next.setSnapToAxis(isSnapToAxis());
        next.setPartPitch(getPartPitch());
        next.setFeedPitch(getFeedPitch());
        next.setCalibrationTrigger(getCalibrationTrigger());
        next.setPrecisionWanted(getPrecisionWanted());
        next.setPipelineType(getPipelineType());
        next.setPipeline(getPipeline().clone());
        next.calibrationToleranceMm = calibrationToleranceMm;
        next.sprocketHoleToleranceMm = sprocketHoleToleranceMm;
        next.calibrateMaxPasses = calibrateMaxPasses;
        next.calibrateToleranceMm = calibrateToleranceMm;
        next.calibrateMinStatistic = calibrateMinStatistic;
        next.setFeedAfterPick(isFeedAfterPick());
        next.setFeedDelayMilliseconds(getFeedDelayMilliseconds());
        next.setFeedCompletionTimeoutMilliseconds(getFeedCompletionTimeoutMilliseconds());
        next.setMoveBeforeFeed(isMoveBeforeFeed());
        next.setFeedRetryCount(getFeedRetryCount());
        next.setPickRetryCount(getPickRetryCount());
        next.setEnabled(false);
        Configuration.get().getMachine().addFeeder(next);
        return next;
    }
}
