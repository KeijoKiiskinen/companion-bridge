package fi.companion.bridge;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise actual event wiring and JSON, without starting a RuneLite client. */
public class CompanionBridgePluginTest {
    private void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }

    @Test public void otherPlayerPickup() throws Exception { assertPickup(TileItem.OWNERSHIP_OTHER, "OTHER"); }
    @Test public void selfPickup() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, "SELF"); }
    @Test public void groupPickup() throws Exception { assertPickup(TileItem.OWNERSHIP_GROUP, "GROUP"); }
    @Test public void unknownPickupStaysUnknown() throws Exception { assertPickup(TileItem.OWNERSHIP_NONE, "UNKNOWN"); }
    private enum Scenario { NORMAL, INVENTORY_FIRST, NO_GAIN, CANCEL, DISCONNECT, OWNER_CHANGED,
        BAD_NAME, LATE, DUPLICATE, SHUTDOWN, QUEUE_FULL }
    @Test public void inventoryBeforeGroundStillCounts() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, "SELF", Scenario.INVENTORY_FIRST); }
    @Test public void otherPlayerRemovalWithoutGainDoesNotCount() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.NO_GAIN); }
    @Test public void anotherClickCancelsAttempt() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.CANCEL); }
    @Test public void disconnectCancelsAttempt() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.DISCONNECT); }
    @Test public void ownershipChangeStaysUnknown() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, "UNKNOWN", Scenario.OWNER_CHANGED); }
    @Test public void controlCharactersDoNotReachExport() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.BAD_NAME); }
    @Test public void expiredAttemptDoesNotCount() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.LATE); }
    @Test public void repeatedTickDoesNotDuplicateEvent() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, "SELF", Scenario.DUPLICATE); }
    @Test public void disablingDrainsAcceptedWrite() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, "SELF", Scenario.SHUTDOWN); }
    @Test public void fullQueueRejectsExportWithoutBlockingGameThread() throws Exception { assertPickup(TileItem.OWNERSHIP_SELF, null, Scenario.QUEUE_FULL); }
    @Test public void restartDoesNotCreateWorkersWhileOldWriterIsBlocked() throws Exception {
        CompanionBridgePlugin plugin = new CompanionBridgePlugin();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        plugin.startUp();
        ExecutorService writer = (ExecutorService) field(plugin, "writer");
        writer.submit(() -> { running.countDown(); try { release.await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        try {
            plugin.shutDown();
            try { plugin.startUp(); fail("new writer started while prior writer blocked"); }
            catch (IllegalStateException expected) { }
            assertSame(writer, field(plugin, "writer"));
        } finally { release.countDown(); assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS)); }
        plugin.startUp(); plugin.shutDown();
    }
    private void assertPickup(int ownership, String expectedOrigin) throws Exception {
        assertPickup(ownership, expectedOrigin, Scenario.NORMAL);
    }
    private void assertPickup(int ownership, String expectedOrigin, Scenario scenario) throws Exception {
        CompanionBridgePlugin plugin = new CompanionBridgePlugin();
        Client client = mock(Client.class); Player player = mock(Player.class);
        WorldView view = mock(WorldView.class); Scene scene = mock(Scene.class);
        Tile tile = mock(Tile.class); TileItem item = mock(TileItem.class);
        ItemManager items = mock(ItemManager.class); ItemComposition definition = mock(ItemComposition.class);
        ItemContainer inventory = mock(ItemContainer.class);
        WorldPoint point = new WorldPoint(100, 200, 0);
        when(client.getLocalPlayer()).thenReturn(player); when(player.getName()).thenReturn("Test account");
        when(player.getWorldLocation()).thenReturn(point); when(client.getAccountHash()).thenReturn(-999991L);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(client.getTickCount()).thenReturn(10);
        when(client.getTopLevelWorldView()).thenReturn(view); when(view.getScene()).thenReturn(scene);
        Tile[][][] tiles = new Tile[1][2][2]; tiles[0][1][1] = tile;
        when(scene.getTiles()).thenReturn(tiles); when(view.getPlane()).thenReturn(0);
        when(tile.getWorldLocation()).thenReturn(point); when(tile.getGroundItems()).thenReturn(List.of(item));
        when(item.getId()).thenReturn(1359); when(item.getQuantity()).thenReturn(1);
        when(item.getOwnership()).thenReturn(ownership);
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
        when(inventory.getItems()).thenReturn(new Item[0]);
        when(items.getItemComposition(1359)).thenReturn(definition); when(definition.getName()).thenReturn("Rune axe");
        when(items.getItemPrice(1359)).thenReturn(7000L);
        if (scenario == Scenario.BAD_NAME) { when(definition.getName()).thenReturn("Rune\naxe"); }
        inject(plugin, "client", client); inject(plugin, "itemManager", items); inject(plugin, "gson", new Gson());
        Path exportRoot = Files.createTempDirectory("companion-plugin-test");
        inject(plugin, "exportRoot", exportRoot);
        plugin.startUp();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            if (scenario == Scenario.QUEUE_FULL) {
                java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
                ExecutorService blockedWriter = (ExecutorService) field(plugin, "writer");
                blockedWriter.submit(() -> { running.countDown(); try { release.await(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
                assertTrue(running.await(2, TimeUnit.SECONDS));
                for (int i=0; i<256; i++) { blockedWriter.submit(() -> { }); }
                inject(plugin, "lastErrorTick", 10);
            }
            MenuEntry entry = mock(MenuEntry.class); when(entry.getWorldViewId()).thenReturn(-1);
            when(entry.getParam0()).thenReturn(1); when(entry.getParam1()).thenReturn(1);
            when(entry.getIdentifier()).thenReturn(1359); when(entry.getType()).thenReturn(MenuAction.GROUND_ITEM_THIRD_OPTION);
            when(entry.getOption()).thenReturn("Take");
            plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
            if (scenario == Scenario.CANCEL) {
                MenuEntry other = mock(MenuEntry.class);
                when(other.getType()).thenReturn(MenuAction.RUNELITE);
                plugin.onMenuOptionClicked(new MenuOptionClicked(other));
            }
            if (scenario == Scenario.DISCONNECT) {
                GameStateChanged change = new GameStateChanged(); change.setGameState(GameState.LOGIN_SCREEN);
                plugin.onGameStateChanged(change);
            }
            if (scenario == Scenario.OWNER_CHANGED) { when(item.getOwnership()).thenReturn(TileItem.OWNERSHIP_OTHER); }
            if (scenario == Scenario.LATE) { when(client.getTickCount()).thenReturn(41); }
            when(inventory.getItems()).thenReturn(new Item[]{new Item(1359, 1)});
            if (scenario == Scenario.INVENTORY_FIRST) {
                plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventory));
            }
            plugin.onItemDespawned(new ItemDespawned(tile, item));
            if (scenario != Scenario.INVENTORY_FIRST && scenario != Scenario.NO_GAIN) {
                plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventory));
            }
            plugin.onGameTick(new GameTick());
            if (scenario == Scenario.QUEUE_FULL) {
                assertEquals("queue_full", ((java.util.concurrent.atomic.AtomicReference<?>) field(plugin, "writeError")).get());
            }
            if (scenario == Scenario.DUPLICATE) { plugin.onGameTick(new GameTick()); }
            ExecutorService writer = (ExecutorService) field(plugin, "writer");
            release.countDown();
            if (scenario == Scenario.SHUTDOWN) { plugin.shutDown(); }
            else { writer.shutdown(); }
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
            Path file = exportRoot.resolve("-999991")
                .resolve("pickups-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
            if (expectedOrigin == null) { assertFalse("Unconfirmed/invalid pickup exported", Files.exists(file)); return; }
            List<String> lines = Files.readAllLines(file);
            assertEquals("Exactly one pickup event", 1, lines.size());
            Map<?, ?> row = new Gson().fromJson(lines.get(lines.size() - 1), Map.class);
            assertFalse(row.containsKey("accountHash")); assertFalse(row.containsKey("world"));
            assertFalse(row.containsKey("x")); assertFalse(row.containsKey("y"));
            assertEquals(expectedOrigin, row.get("origin")); assertEquals("Rune axe", row.get("name"));
            assertEquals(1.0, row.get("quantity")); assertEquals("take+ground_decrease+inventory_increase", row.get("evidence"));
            Files.delete(file); Files.delete(file.getParent());
        } finally {
            release.countDown();
            plugin.shutDown();
            try (java.util.stream.Stream<Path> paths = Files.walk(exportRoot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
