package fi.companion.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;

@PluginDescriptor(name = "Companion Bridge", description = "Local ground pickup export for OSRS Companion",
    tags = {"companion", "loot", "export"}, enabledByDefault = false)
public class CompanionBridgePlugin extends Plugin {
    private static final int MAX_HINTS = 256, MAX_GROUND = 4096, MAX_MANUAL_DROPS = 256;
    @Inject private Client client;
    @Inject private ItemManager itemManager;
    @Inject private Gson gson;

    private final PickupDetector detector = new PickupDetector();
    private final Map<TileItem, Ground> ground = new IdentityHashMap<>();
    private final List<DropHint> hints = new ArrayList<>();
    private final Map<Integer, Integer> manualDrops = new HashMap<>();
    private ExecutorService writer;
    private WorldPoint pendingTile;
    private String pendingOrigin = "UNKNOWN", pendingSource = "";
    private int pendingOwner;
    private AtomicReference<String> writeError = new AtomicReference<>();
    private int lastErrorTick = -100;
    // Tests replace this with a temporary directory, never the real RuneLite folder.
    private Path exportRoot = RuneLite.RUNELITE_DIR.toPath().resolve("companion-bridge");

    private static final class Ground {
        final WorldPoint tile; final int id, tick; final boolean manual;
        final java.util.Set<String> sources = new java.util.HashSet<>();
        Ground(WorldPoint tile, int id, int tick, boolean manual) { this.tile = tile; this.id = id; this.tick = tick; this.manual = manual; }
    }
    private static final class DropHint {
        final int id, tick; final String source;
        DropHint(int id, int tick, String source) { this.id = id; this.tick = tick; this.source = source; }
    }

    @Override protected void startUp() {
        if (writer != null && !writer.isTerminated()) {
            throw new IllegalStateException("Bridge writer still running; retry after pending export finishes");
        }
        writeError = new AtomicReference<>();
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            r -> { Thread t = new Thread(r, "companion-export"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
        clear();
        writeError.set(null);
        lastErrorTick = -100;
    }
    @Override protected void shutDown() {
        clear();
        // Drain accepted writes without interrupting a JSON line or blocking the game thread.
        // A process crash can still interrupt a write; the writer then rejects its unfinished tail.
        if (writer != null) { writer.shutdown(); }
    }
    private void clear() { detector.cancel(); ground.clear(); hints.clear(); manualDrops.clear(); pendingTile = null; }

    @Subscribe public void onGameStateChanged(GameStateChanged e) {
        if (e.getGameState() != GameState.LOGGED_IN) { clear(); }
    }
    @Subscribe public void onLootReceived(LootReceived e) {
        if (!"NPC".equals(e.getType().name()) || !validText(e.getName(), 256)) { return; }
        int tick = client.getTickCount();
        for (ItemStack item : e.getItems()) {
            if (hints.size() < MAX_HINTS) { hints.add(new DropHint(item.getId(), tick, e.getName())); }
            for (Map.Entry<TileItem, Ground> entry : ground.entrySet()) {
                Ground g = entry.getValue();
                if (g.id == item.getId() && Math.abs(g.tick - tick) <= 2 && !g.manual
                        && entry.getKey().getOwnership() == TileItem.OWNERSHIP_SELF
                        && g.sources.size() < 2) { g.sources.add(e.getName()); }
            }
        }
    }
    @Subscribe public void onItemSpawned(ItemSpawned e) {
        TileItem item = e.getItem(); int tick = client.getTickCount();
        boolean manual = manualDrops.getOrDefault(item.getId(), -100) >= tick - 2;
        Ground g = new Ground(e.getTile().getWorldLocation(), item.getId(), tick, manual);
        if (!manual && item.getOwnership() == TileItem.OWNERSHIP_SELF) {
            for (DropHint hint : hints) { if (hint.id == item.getId() && Math.abs(hint.tick - tick) <= 2
                    && g.sources.size() < 2) { g.sources.add(hint.source); } }
        }
        if (ground.size() < MAX_GROUND) { ground.put(item, g); }
    }
    @Subscribe public void onItemDespawned(ItemDespawned e) {
        recordRemoval(e.getTile(), e.getItem(), e.getItem().getQuantity()); ground.remove(e.getItem());
    }
    @Subscribe public void onItemQuantityChanged(ItemQuantityChanged e) {
        recordRemoval(e.getTile(), e.getItem(), e.getOldQuantity() - e.getNewQuantity());
    }
    private void recordRemoval(Tile tile, TileItem item, int quantity) {
        if (detector.isPending() && item.getId() == detector.getItemId()
                && pendingTile != null && pendingTile.equals(tile.getWorldLocation())) {
            if (item.getOwnership() != pendingOwner) {
                pendingOrigin = "UNKNOWN"; pendingSource = "";
            }
            detector.groundRemoved(item.getId(), quantity, client.getTickCount(), nearPending());
            // If multiple same-ID stacks had different origins, never choose an owner by guess.
        }
    }
    private boolean nearPending() {
        return pendingTile != null && client.getLocalPlayer() != null
            && client.getLocalPlayer().getWorldLocation().distanceTo(pendingTile) <= 1;
    }
    private int inventoryCount(int id) {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) { return 0; }
        long count = 0;
        for (Item item : inventory.getItems()) { if (item.getId() == id) { count += item.getQuantity(); } }
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e) {
        detector.cancel(); pendingTile = null;
        if (writer == null || writer.isShutdown()) { return; }
        if ("Drop".equals(e.getMenuOption()) && e.getItemId() > 0
                && (manualDrops.containsKey(e.getItemId()) || manualDrops.size() < MAX_MANUAL_DROPS)) {
            manualDrops.put(e.getItemId(), client.getTickCount());
        }
        MenuAction action = e.getMenuAction();
        boolean groundAction = action == MenuAction.GROUND_ITEM_FIRST_OPTION || action == MenuAction.GROUND_ITEM_SECOND_OPTION
            || action == MenuAction.GROUND_ITEM_THIRD_OPTION || action == MenuAction.GROUND_ITEM_FOURTH_OPTION
            || action == MenuAction.GROUND_ITEM_FIFTH_OPTION;
        if (!groundAction || !"Take".equals(e.getMenuOption()) || client.getGameState() != GameState.LOGGED_IN) { return; }
        // This first version intentionally handles only the top-level world view.
        if (e.getMenuEntry().getWorldViewId() != -1) { return; }
        if (client.getTopLevelWorldView() == null) { return; }
        int x = e.getParam0(), y = e.getParam1();
        Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
        int plane = client.getTopLevelWorldView().getPlane();
        if (plane < 0 || plane >= tiles.length || x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length) { return; }
        Tile tile = tiles[plane][x][y];
        if (tile == null || tile.getGroundItems() == null) { return; }
        List<TileItem> candidates = new ArrayList<>();
        for (TileItem item : tile.getGroundItems()) { if (item.getId() == e.getId()) { candidates.add(item); } }
        if (candidates.isEmpty()) { return; }
        if (client.getItemContainer(InventoryID.INVENTORY) == null) { return; }
        pendingTile = tile.getWorldLocation(); pendingOrigin = "UNKNOWN"; pendingSource = "";
        pendingOwner = candidates.get(0).getOwnership();
        boolean uniform = candidates.stream().allMatch(i -> i.getOwnership() == pendingOwner);
        boolean manual = candidates.stream().anyMatch(i -> ground.containsKey(i) && ground.get(i).manual);
        if (uniform && !manual) {
            if (pendingOwner == TileItem.OWNERSHIP_OTHER) { pendingOrigin = "OTHER"; }
            else if (pendingOwner == TileItem.OWNERSHIP_GROUP) { pendingOrigin = "GROUP"; }
            else if (pendingOwner == TileItem.OWNERSHIP_SELF) { pendingOrigin = "SELF"; }
        } else if (uniform && candidates.stream().allMatch(i -> ground.containsKey(i) && ground.get(i).manual)
                && pendingOwner == TileItem.OWNERSHIP_SELF) { pendingOrigin = "SELF_DROPPED"; }
        // A hint is explicitly an estimate: modern NPC loot events have no drop tile.
        // Hints were attached near spawn time and are used only for self-owned ground items.
        if ("SELF".equals(pendingOrigin)) {
            java.util.Set<String> sources = new java.util.HashSet<>();
            for (TileItem item : candidates) { if (ground.containsKey(item)) { sources.addAll(ground.get(item).sources); } }
            if (sources.size() == 1) { pendingSource = sources.iterator().next(); }
        }
        detector.begin(e.getId(), inventoryCount(e.getId()), client.getTickCount());
    }

    @Subscribe public void onItemContainerChanged(ItemContainerChanged e) {
        if (e.getContainerId() == InventoryID.INVENTORY.getId() && detector.isPending()) {
            detector.inventoryChanged(inventoryCount(detector.getItemId()), client.getTickCount());
        }
    }
    @Subscribe public void onGameTick(GameTick e) {
        int tick = client.getTickCount();
        hints.removeIf(h -> tick - h.tick > 2); // Source hint only, never used as ownership proof.
        manualDrops.entrySet().removeIf(entry -> tick - entry.getValue() > 2);
        int quantity = detector.settle(tick, nearPending());
        if (quantity > 0) { writePickup(detector.getItemId(), quantity); }
        if (writeError.get() != null && tick - lastErrorTick >= 50) {
            writeError.getAndSet(null);
            lastErrorTick = tick;
            client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Companion Bridge: paikallisen viennin tallennus epäonnistui. Tarkista levytila ja lokikansion eheys.", null);
        }
    }
    private static boolean validText(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit) { return false; }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c)
                    || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) { return false; }
        }
        return true;
    }
    private void writePickup(int id, int quantity) {
        if (writer == null || writer.isShutdown() || client.getLocalPlayer() == null) { return; }
        String account = Long.toString(client.getAccountHash());
        String playerName = client.getLocalPlayer().getName();
        if (playerName == null || playerName.trim().isEmpty()) { return; }
        String player = Text.removeTags(playerName);
        net.runelite.api.ItemComposition definition = itemManager.getItemComposition(id);
        String name = definition == null ? null : definition.getName();
        long price = itemManager.getItemPrice(id);
        if (id <= 0 || quantity <= 0 || price < 0 || price > 1_000_000_000_000L
                || !validText(player, 12) || !validText(name, 256)
                || (!pendingSource.isEmpty() && !validText(pendingSource, 256))) {
            writeError.set("invalid_record"); return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", 1); row.put("eventId", UUID.randomUUID().toString()); row.put("player", player);
        row.put("timestamp", Instant.now().toString());
        row.put("itemId", id); row.put("name", name);
        row.put("quantity", quantity); row.put("unitPrice", price);
        row.put("origin", pendingOrigin); row.put("ownership", pendingOwner);
        row.put("sourceHint", pendingSource); row.put("sourceHintIsEstimate", true);
        // World and coordinates are unnecessary for Companion; omit them for privacy.
        row.put("evidence", "take+ground_decrease+inventory_increase");
        String line = gson.toJson(row) + "\n";
        Path file = exportRoot.resolve(account)
            .resolve("pickups-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
        AtomicReference<String> errors = writeError;
        try {
            writer.submit(() -> {
                try { PickupLogWriter.append(file, line); }
                catch (IOException | SecurityException ex) { errors.set("local_write_failed"); }
            });
        } catch (RejectedExecutionException ex) {
            writeError.set("queue_full"); // Never block the game thread or grow without bound.
        }
    }
}
