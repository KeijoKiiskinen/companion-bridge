package fi.companion.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class PickupDetectorTest {
    @Test public void lateEvidenceCancelsWithoutWaitingForGameTick() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.inventoryChanged(1, 32); d.groundRemoved(100, 1, 32, true);
        assertFalse(d.isPending()); assertEquals(0, d.settle(32, true));
    }
    @Test public void backwardsTicksCannotConfirmPickup() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 10);
        d.groundRemoved(100, 1, 9, true); d.inventoryChanged(1, 9);
        assertFalse(d.isPending()); assertEquals(0, d.settle(10, true));
    }
    @Test public void invalidBaselineAndInventoryAreRejected() {
        PickupDetector d = new PickupDetector(); d.begin(0, 0, 1);
        assertFalse(d.isPending()); d.begin(100, -1, 1); assertFalse(d.isPending());
        d.begin(100, 1, 1); d.inventoryChanged(-1, 1); assertFalse(d.isPending());
    }
    @Test public void inventoryDecreaseInvalidatesGroundEvidenceToo() {
        PickupDetector d = new PickupDetector(); d.begin(100, 1, 1);
        d.groundRemoved(100, 1, 1, true); d.inventoryChanged(2, 1);
        d.inventoryChanged(1, 1); d.inventoryChanged(2, 1);
        assertEquals(0, d.settle(1, true));
    }
    @Test public void hugeQuantitiesDoNotOverflow() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.groundRemoved(100, Integer.MAX_VALUE, 1, true);
        d.groundRemoved(100, Integer.MAX_VALUE, 1, true);
        d.inventoryChanged(Integer.MAX_VALUE, 1);
        assertEquals(Integer.MAX_VALUE, d.settle(1, true));
    }
    @Test public void clickAloneIsNotPickup() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        assertEquals(0, d.settle(2, true));
    }
    @Test public void requiresBothInventoryAndGroundEvidence() {
        PickupDetector d = new PickupDetector(); d.begin(100, 3, 1);
        d.inventoryChanged(8, 2); assertEquals(0, d.settle(2, true));
        d.groundRemoved(100, 5, 2, true); assertEquals(5, d.settle(2, true));
        assertEquals(0, d.settle(3, true));
    }
    @Test public void fullInventoryOrOtherPlayerRemovalDoesNotCount() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.groundRemoved(100, 1, 2, true); assertEquals(0, d.settle(2, true));
    }
    @Test public void wrongItemAndDistantRemovalDoNotCount() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.inventoryChanged(1, 2); d.groundRemoved(101, 1, 2, true);
        d.groundRemoved(100, 1, 2, false); assertEquals(0, d.settle(2, true));
    }
    @Test public void partialPickupUsesSmallerQuantity() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.inventoryChanged(3, 2); d.groundRemoved(100, 20, 2, true);
        assertEquals(3, d.settle(2, true));
    }
    @Test public void oldInventoryGainCannotConfirmLaterDespawn() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        d.inventoryChanged(1, 2); d.groundRemoved(100, 1, 10, true);
        assertEquals(0, d.settle(10, true));
    }
    @Test public void attemptExpiresAndCancelSuppressesBanking() {
        PickupDetector d = new PickupDetector(); d.begin(100, 0, 1);
        assertEquals(0, d.settle(32, true)); assertFalse(d.isPending());
        d.begin(100, 0, 33); d.cancel(); d.inventoryChanged(10, 2);
        d.groundRemoved(100, 10, 34, true); assertEquals(0, d.settle(34, true));
    }
}
