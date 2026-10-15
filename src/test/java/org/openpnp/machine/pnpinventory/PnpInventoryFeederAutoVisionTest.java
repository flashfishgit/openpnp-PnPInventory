package org.openpnp.machine.pnpinventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

class PnpInventoryFeederAutoVisionTest {
    private final List<String> events = new ArrayList<>();
    private TestFeeder feeder;
    private Nozzle nozzle;
    private Actuator actuator;

    private class TestFeeder extends PnpInventoryFeederAutoVision {
        @Override
        public void assertCalibrated(boolean tapeFeed) {
            events.add("vision");
        }

        @Override
        protected void waitForFeed() {
            events.add("delay:" + getFeedDelayMilliseconds());
        }

        @Override
        protected void actuatePostPick(Nozzle nozzle) {
            events.add("post");
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Configuration.initialize();
        Configuration.get().setMachine(mock(Machine.class));
        feeder = new TestFeeder();
        nozzle = mock(Nozzle.class);
        Head head = mock(Head.class);
        actuator = mock(Actuator.class);
        when(nozzle.getHead()).thenReturn(head);
        when(actuator.getName()).thenReturn("feed");
        when(head.getActuatorByName("feed")).thenReturn(actuator);
        doAnswer(invocation -> {
            events.add("feed");
            return null;
        })
                .when(actuator).actuate(any(Object.class));
        feeder.setFeedActuator(actuator);
        feeder.setFeedDelayMilliseconds(250);
    }

    @Test
    void reverseOrderWaitsBeforePostAction() throws Exception {
        feeder.setFeedAfterPick(true);
        feeder.feed(nozzle);
        events.add("pick");
        feeder.postPick(nozzle);
        assertEquals(Arrays.asList("vision", "pick", "feed", "delay:250", "post"), events);
        assertFalse(feeder.canTakeBackPart());
    }

    @Test
    void normalOrderIsPreserved() throws Exception {
        feeder.feed(nozzle);
        events.add("pick");
        feeder.postPick(nozzle);
        assertEquals(Arrays.asList("vision", "feed", "delay:250", "vision", "pick", "post"), events);
    }

    @Test
    void multiplePartsAdvanceOnlyAfterLastPick() throws Exception {
        feeder.setFeedAfterPick(true);
        feeder.setPartPitch(new Length(2, LengthUnit.Millimeters));
        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        assertFalse(events.contains("feed"));
        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        assertEquals(1, events.stream().filter("feed"::equals).count());
        assertEquals(2, feeder.getFeedCount());
    }

    @Test
    void largePartPitchWaitsAfterEveryActuation() throws Exception {
        feeder.setFeedAfterPick(true);
        feeder.setPartPitch(new Length(8, LengthUnit.Millimeters));
        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        assertEquals(Arrays.asList("vision", "feed", "delay:250", "feed", "delay:250", "post"), events);
        feeder.postPick(nozzle);
        assertEquals(2, events.stream().filter("feed"::equals).count());
    }

    @Test
    void failedAdvanceCannotSilentlyRetryOrRunPostAction() throws Exception {
        feeder.setFeedAfterPick(true);
        doThrow(new Exception("Feed failed")).when(actuator).actuate(any(Object.class));
        feeder.feed(nozzle);
        assertThrows(Exception.class, () -> feeder.postPick(nozzle));
        assertThrows(Exception.class, () -> feeder.postPick(nozzle));
        assertThrows(Exception.class, () -> feeder.feed(nozzle));
        assertFalse(events.contains("post"));
        verify(actuator, times(1)).actuate(any(Object.class));
    }

    @Test
    void negativeDelayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> feeder.setFeedDelayMilliseconds(-1));
    }
}
