/*
 * Copyright (c) 2020, Harrison <https://github.com/hBurt>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.runecraftingtracker;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.api.gameval.InventoryID;
import java.util.Map;
import java.util.HashMap;

@Slf4j
@PluginDescriptor(
        name = "Runecrafting Tracker",
        description = "Track your total profit and the amount of runes you have crafted",
        tags = {"rc", "rune", "craft", "runecraft", "runecrafting", "track", "tracker", "zmi", "ourania", "altar"}
)
public class RunecraftingTrackerPlugin extends Plugin
{
    private RunecraftingTrackerPanel uiPanel;

    private final int[] runeIDs = {556, 558, 555, 557, 554, 559, 564, 562, 9075, 561, 563, 560, 565, 566, 21880, 4695, 4696, 4698, 4697, 4694, 4699};
    private NavigationButton uiNavigationButton;
    private final LinkedList<PanelItemData> runeTracker = new LinkedList<>();
    private Multiset<Integer> inventorySnapshot;
    private final Set<Integer> runeIdSet = Arrays.stream(runeIDs).boxed().collect(Collectors.toSet());

    @Inject
    @SuppressWarnings("unused")
    private ClientToolbar clientToolbar;

    @Inject
    @SuppressWarnings("unused")
    private Client client;

    @Inject
    @SuppressWarnings("unused")
    private ClientThread clientThread;

    @Inject
    @SuppressWarnings("unused")
    private ItemManager manager;

    // Injected manager for rune pouch logic
    @Inject
    @SuppressWarnings("unused")
    private RunePouchManager runePouchManager;

    // Cache of last known rune pouch contents (itemId -> qty) to avoid noisy logs/refreshes
    private final Map<Integer, Integer> lastRunePouch = new HashMap<>();

    @Override
    protected void startUp()
    {
        log.debug("RunecraftingTrackerPlugin startUp: initializing UI and priming snapshot");
        log.debug("Expected inventory container id: {}", InventoryID.INV);
        // ImageUtil.getResourceStreamFromClass is deprecated — use loadImageResource which returns a BufferedImage
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        uiPanel = new RunecraftingTrackerPanel(manager, runeTracker);

        uiNavigationButton = NavigationButton.builder()
                .tooltip("Runecrafting Tracker")
                .icon(icon)
                .priority(10)
                .panel(uiPanel)
                .build();

        clientToolbar.addNavigation(uiNavigationButton);
        log.debug("Navigation button added to client toolbar");

        // Prime an initial snapshot so the first ItemContainerChanged can be diffed
        clientThread.invokeLater(() -> {
            takeInventorySnapshot();
            log.debug("Initial inventory snapshot taken at startup");
        });
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(uiNavigationButton);
        lastRunePouch.clear();
    }

    private void init()
    {
        log.debug("Initializing rune tracker items ({} runes)", Runes.values().length);
        for (int i = 0; i < Runes.values().length; i++)
        {
            runeTracker.add(new PanelItemData(
                    Runes.values()[i].name(),
                    runeIDs[i],
                    false,
                    0,
                    manager.getItemPrice(runeIDs[i])));
            log.debug("Added panel item for rune {} (id={})", Runes.values()[i].name(), runeIDs[i]);
        }
    }
    @SuppressWarnings("unused")
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        log.debug("GameStateChanged: {}", event.getGameState());
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            if (runeTracker.isEmpty())
            {
                log.debug("Rune tracker empty at LOGGED_IN; initializing");
                clientThread.invokeLater(this::init);
            }
            // Refresh snapshot on login
            clientThread.invokeLater(() -> {
                takeInventorySnapshot();
                log.debug("Inventory snapshot refreshed at LOGGED_IN");
            });
        }
    }
    @SuppressWarnings("unused")
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        log.debug("StatChanged: skill={} xp={} level={}", event.getSkill(), event.getXp(), event.getLevel());
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null ||
                event.getSkill() != Skill.RUNECRAFT)
        {
            return;
        }
        // Don't overwrite baseline after crafting; only take a snapshot if one doesn't exist yet
        if (inventorySnapshot == null)
        {
            log.debug("Runecraft stat changed; baseline missing; taking inventory snapshot");
            takeInventorySnapshot();
        }
        else
        {
            log.debug("Runecraft stat changed; baseline exists; not taking snapshot to preserve pre-craft state");
        }
    }
    // Keep rune pouch contents up to date when varbits change
    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (runePouchManager == null || client == null)
        {
            return;
        }

        if (runePouchManager.hasNoRunePouch())
        {
            if (!lastRunePouch.isEmpty())
            {
                lastRunePouch.clear();
                log.debug("Rune pouch not present; cleared cached contents");
            }
            return;
        }

        Map<Integer, Integer> current = runePouchManager.readContents();
        if (!current.equals(lastRunePouch))
        {
            if (log.isDebugEnabled())
            {
                String contents = current.entrySet().stream()
                    .map(e -> e.getKey() + "x" + e.getValue())
                    .collect(Collectors.joining(", "));
                log.debug("Rune pouch contents updated: [{}]", contents);
                // Also log a human-readable summary
                log.debug("Rune pouch (readable): {}", runePouchManager.toReadableString());
            }
            lastRunePouch.clear();
            lastRunePouch.putAll(current);

            // Optionally, refresh UI or trigger any dependent logic here if needed
        }
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        // Only react to local player's main inventory changes
        if (event == null)
        {
            return;
        }

        final ItemContainer container = event.getItemContainer();
        final int containerId = event.getContainerId();

        // If there's no container in the event, nothing to do
        if (container == null)
        {
            return;
        }

        // Log the container id and current items length
        log.debug("ItemContainerChanged: containerId={} itemsLength={} snapshotPresent={}", containerId, container.getItems().length, inventorySnapshot != null);

        // Accept only events for the player's main inventory container by id
        if (containerId != InventoryID.INV)
        {
            log.debug("Ignoring ItemContainerChanged: not inventory (containerId={})", containerId);
            return;
        }

        if (inventorySnapshot == null)
        {
            // No baseline to diff from; take a snapshot so future changes can be diffed
            log.debug("No inventory snapshot present; taking snapshot and deferring processing");
            takeInventorySnapshot();
            return;
        }

        processChange(container);
    }

    private void processChange(ItemContainer current)
    {
        if (current == null || client.getLocalPlayer() == null)
        {
            return;
        }

        // Create inventory multiset {itemId -> quantity}, ignoring empty slots and invalid ids
        Multiset<Integer> currentInventory = HashMultiset.create();
        Arrays.stream(current.getItems())
                .filter(item -> item != null && item.getId() > 0 && item.getQuantity() > 0)
                .forEach(item -> currentInventory.add(item.getId(), item.getQuantity()));

        if (log.isDebugEnabled())
        {
            // Log summary of rune counts in current inventory
            String currentRuneSummary = currentInventory.entrySet().stream()
                    .filter(e -> runeIdSet.contains(e.getElement()))
                    .map(e -> e.getElement() + "x" + e.getCount())
                    .collect(Collectors.joining(", "));
            log.debug("Current rune counts: [{}]", currentRuneSummary);
            log.debug("Current inventory snapshot size={} distinctItems={}",
                    currentInventory.size(), currentInventory.elementSet().size());
        }

        if (inventorySnapshot == null)
        {
            // If somehow missing, set it now and bail
            inventorySnapshot = currentInventory;
            log.debug("Inventory snapshot was null inside processChange; set to current and return");
            return;
        }

        // Get inventory increases relative to snapshot
        // Manually compute positive deltas (current - snapshot) to avoid using Guava's @Beta Multisets.difference
        final Multiset<Integer> diff = HashMultiset.create();
        for (Integer id : currentInventory.elementSet())
        {
            int currentCount = currentInventory.count(id);
            int previousCount = (inventorySnapshot == null) ? 0 : inventorySnapshot.count(id);
            int delta = currentCount - previousCount;
            if (delta > 0)
            {
                diff.add(id, delta);
            }
        }

        // Iterate positive deltas directly (avoid deprecated ItemStack constructor)
        List<Multiset.Entry<Integer>> deltas = diff.entrySet().stream()
                .filter(e -> e.getCount() > 0)
                .collect(Collectors.toList());

        // Focus on rune deltas only for updating UI and snapshot
        List<Multiset.Entry<Integer>> runeDeltas = deltas.stream()
                .filter(e -> runeIdSet.contains(e.getElement()))
                .collect(Collectors.toList());

        if (log.isDebugEnabled() && !deltas.isEmpty())
        {
            log.debug("Found {} positive deltas ({} rune deltas)", deltas.size(), runeDeltas.size());
            for (Multiset.Entry<Integer> delta : deltas)
            {
                final int id = delta.getElement();
                final int qty = delta.getCount();
                final boolean isRune = runeIdSet.contains(id);
                log.debug("Delta: +{} of itemId={}{}", qty, id, isRune ? " (rune)" : "");
            }
        }

        if (!runeDeltas.isEmpty())
        {
            LinkedList<PanelItemData> panels = uiPanel.getRuneTracker();

            for (Multiset.Entry<Integer> delta : runeDeltas)
            {
                final int id = delta.getElement();
                final int qty = delta.getCount();
                for (PanelItemData item : panels)
                {
                    if (item.getId() == id)
                    {
                        if (!item.isVisible())
                        {
                            item.setVisible(true);
                            log.debug("Making rune id={} visible in panel", id);
                        }
                        int newCrafted = item.getCrafted() + qty;
                        item.setCrafted(newCrafted);
                        log.debug("Updated crafted count for rune id={} to {} (+{})", id, newCrafted, qty);
                    }
                }
            }

            // Update snapshot to current state for next diff only after applying rune deltas
            inventorySnapshot = currentInventory;
            log.debug("Inventory snapshot updated after processing rune deltas");

            try
            {
                SwingUtilities.invokeAndWait(() -> {
                    uiPanel.pack();
                    log.debug("UI panel packed after updates");
                });
            }
            catch (InterruptedException | InvocationTargetException e)
            {
                log.warn("Failed to update Runecrafting panel layout synchronously", e);
                // Fallback to async to avoid blocking
                SwingUtilities.invokeLater(() -> {
                    uiPanel.pack();
                    log.debug("UI panel packed (async fallback)");
                });
            }

            uiPanel.refresh();
            log.debug("UI panel refreshed (revalidate + repaint)");
        }
        else if (!deltas.isEmpty())
        {
            // There were positive deltas but none for runes; preserve baseline to catch upcoming rune additions
            log.debug("Positive deltas detected but none were runes; preserving baseline snapshot for next event");
        }
        else
        {
            // No positive deltas; keep baseline as-is to await rune additions
            log.debug("No positive deltas; preserving baseline snapshot for next event");
        }
    }

    private void takeInventorySnapshot()
    {
        final ItemContainer itemContainer = client.getItemContainer(InventoryID.INV);
        if (itemContainer != null)
        {
            inventorySnapshot = HashMultiset.create();
            Arrays.stream(itemContainer.getItems())
                    .filter(item -> item != null && item.getId() > 0 && item.getQuantity() > 0)
                    .forEach(item -> inventorySnapshot.add(item.getId(), item.getQuantity()));
            if (log.isDebugEnabled())
            {
                // Log summary of rune counts in snapshot
                String snapshotRuneSummary = inventorySnapshot.entrySet().stream()
                        .filter(e -> runeIdSet.contains(e.getElement()))
                        .map(e -> e.getElement() + "x" + e.getCount())
                        .collect(Collectors.joining(", "));
                log.debug("Snapshot taken: size={} distinctItems={} runes=[{}]",
                        inventorySnapshot.size(), inventorySnapshot.elementSet().size(), snapshotRuneSummary);
            }
        }
        else
        {
            log.debug("Snapshot skipped: inventory ItemContainer is null");
        }
    }

    @SuppressWarnings("unused")
    protected LinkedList<PanelItemData> getRuneTracker()
    {
        return runeTracker;
    }

    enum Runes
    {AIR, MIND, WATER, EARTH, FIRE, BODY, COSMIC, CHAOS, ASTRAL, NATURE, LAW, DEATH, BLOOD, SOUL, WRATH, MIST, DUST, MUD, SMOKE, STEAM, LAVA}
}
