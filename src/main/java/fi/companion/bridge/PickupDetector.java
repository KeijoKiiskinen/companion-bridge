package fi.companion.bridge;

/** Conservative pickup evidence; no RuneLite dependency so the rules can be tested. */
public final class PickupDetector {
    private int itemId, inventory, removed, gained, startedTick, lastTick;
    private boolean pending;
    private int gainedTick, removedTick;

    public void begin(int id, int currentInventory, int tick) {
        if (id <= 0 || currentInventory < 0 || tick < 0) {
            cancel(); return;
        }
        itemId = id; inventory = currentInventory; startedTick = tick;
        lastTick = tick;
        removed = 0; gained = 0; gainedTick = -100; removedTick = -100; pending = true;
    }

    public void cancel() { pending = false; }
    public boolean isPending() { return pending; }
    public int getItemId() { return itemId; }

    private boolean current(int tick) {
        if (!pending) { return false; }
        long age = (long) tick - startedTick;
        if (age < 0 || age > 30 || tick < lastTick) { cancel(); return false; }
        lastTick = tick;
        return true;
    }

    public void groundRemoved(int id, int quantity, int tick, boolean nearTile) {
        if (current(tick) && id == itemId && nearTile && quantity > 0) {
            if (tick - removedTick > 1) { removed = 0; }
            removed = (int) Math.min(Integer.MAX_VALUE, (long) removed + quantity);
            removedTick = tick;
        }
    }

    public void inventoryChanged(int quantity, int tick) {
        if (!current(tick)) { return; }
        if (quantity < 0) { cancel(); return; }
        // Negative changes invalidate previous gain evidence, rather than guessing a pickup.
        if (quantity < inventory) { gained = 0; removed = 0; }
        else if (quantity > inventory) {
            if (tick - gainedTick > 1) { gained = 0; }
            gained = (int) Math.min(Integer.MAX_VALUE, (long) gained + quantity - inventory);
            gainedTick = tick;
        }
        inventory = quantity;
    }

    public int settle(int tick, boolean nearTile) {
        if (!current(tick)) { return 0; }
        if (!nearTile) { return 0; }
        if (Math.abs(gainedTick - removedTick) > 1 || tick - gainedTick > 1 || tick - removedTick > 1) { return 0; }
        int confirmed = Math.min(gained, removed);
        if (confirmed > 0) { cancel(); }
        return confirmed;
    }
}
