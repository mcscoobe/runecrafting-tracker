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
import net.runelite.api.gameval.ItemID;
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

    private final int[] runeIDs = {
        ItemID.AIRRUNE,
        ItemID.MINDRUNE,
        ItemID.WATERRUNE,
        ItemID.EARTHRUNE,
        ItemID.FIRERUNE,
        ItemID.BODYRUNE,
        ItemID.COSMICRUNE,
        ItemID.CHAOSRUNE,
        ItemID.ASTRALRUNE,
        ItemID.NATURERUNE,
        ItemID.LAWRUNE,
        ItemID.DEATHRUNE,
        ItemID.BLOODRUNE,
        ItemID.SOULRUNE,
        ItemID.WRATHRUNE,
        ItemID.MISTRUNE,
        ItemID.DUSTRUNE,
        ItemID.MUDRUNE,
        ItemID.SMOKERUNE,
        ItemID.STEAMRUNE,
        ItemID.LAVARUNE
    };
    private NavigationButton uiNavigationButton;
    private final LinkedList<PanelItemData> runeTracker = new LinkedList<>();
    private Multiset<Integer> inventorySnapshot;
    private final Set<Integer> runeIdSet = Arrays.stream(runeIDs).boxed().collect(Collectors.toSet());
    // New: direct index from itemId -> PanelItemData to avoid nested loops
    private final Map<Integer, PanelItemData> panelById = new HashMap<>();

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

    private RunePouchManager runePouchManager;

    // Cache of last known rune pouch contents (itemId -> qty) to avoid noisy logs/refreshes
    private final Map<Integer, Integer> lastRunePouch = new HashMap<>();
    private boolean pouchBaselineReady = false;

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

        runePouchManager = new RunePouchManager(client);
        log.debug("Initialized RunePouchManager");

        // Prime an initial snapshot so the first ItemContainerChanged can be diffed
        clientThread.invokeLater(() -> {
            takeInventorySnapshot();
            if (runePouchManager != null && !runePouchManager.hasNoRunePouch())
            {
                lastRunePouch.clear();
                lastRunePouch.putAll(runePouchManager.readContents());
                pouchBaselineReady = true;
                log.debug("Initialized rune pouch baseline at startup: {}", runePouchManager.toReadableString());
            }
            log.debug("Initial inventory snapshot taken at startup");
        });
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(uiNavigationButton);
        lastRunePouch.clear();
        runePouchManager = null;
    }

    private void init()
    {
        log.debug("Initializing rune tracker items ({} runes)", Runes.values().length);
        for (int i = 0; i < Runes.values().length; i++)
        {
            final PanelItemData pid = new PanelItemData(
                    Runes.values()[i].name(),
                    runeIDs[i],
                    false,
                    0,
                    manager.getItemPrice(runeIDs[i]));
            runeTracker.add(pid);
            panelById.put(runeIDs[i], pid);
            log.debug("Added panel item for rune {} (id={})", Runes.values()[i].name(), runeIDs[i]);
        }
        // One-time mapping dump for key runes to verify UI rows align with item IDs
        if (log.isDebugEnabled())
        {
            int[] keys = {559, 561, 563, 564}; // BODY, NATURE, LAW, COSMIC
            String map = Arrays.stream(keys)
                    .mapToObj(id -> id + "->" + (panelById.get(id) != null ? panelById.get(id).getName() : "(null)"))
                    .collect(Collectors.joining(", "));
            log.debug("Panel mapping: {}", map);
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
            // Refresh snapshot and pouch baseline on login
            clientThread.invokeLater(() -> {
                takeInventorySnapshot();
                if (runePouchManager != null && !runePouchManager.hasNoRunePouch())
                {
                    lastRunePouch.clear();
                    lastRunePouch.putAll(runePouchManager.readContents());
                    pouchBaselineReady = true;
                    log.debug("Initialized rune pouch baseline at login: {}", runePouchManager.toReadableString());
                }
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
        if (inventorySnapshot == null)
        {
            takeInventorySnapshot();
        }
        // Also check pouch right away when Runecraft xp changes (craft happened)
        updateRunePouchDeltas("stat");
    }

    // Keep rune pouch contents up to date when varbits change
    @SuppressWarnings("unused")
    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (event == null || runePouchManager == null || client == null)
        {
            return;
        }

        final int changedVarBitId = event.getVarbitId();
        if (changedVarBitId != -1 && !runePouchManager.isRunePouchVarBit(changedVarBitId))
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
                log.debug("Rune pouch (readable): {}", runePouchManager.toReadableString());
            }
            lastRunePouch.clear();
            lastRunePouch.putAll(current);
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
            boolean any = false;
            for (Multiset.Entry<Integer> delta : runeDeltas)
            {
                any |= applyDeltaToPanel(delta.getElement(), delta.getCount(), "inventory");
            }

            inventorySnapshot = currentInventory;

            if (any)
            {
                try
                {
                    SwingUtilities.invokeAndWait(() -> uiPanel.pack());
                }
                catch (InterruptedException | InvocationTargetException e)
                {
                    log.warn("Failed to pack UI synchronously", e);
                    SwingUtilities.invokeLater(uiPanel::pack);
                }
                uiPanel.refresh();
            }
        }
        else if (!deltas.isEmpty())
        {
            log.debug("Positive deltas detected but none were runes; preserving baseline snapshot for next event");
        }
        else
        {
            log.debug("No positive deltas; preserving baseline snapshot for next event");
        }

        // Always check pouch after inventory settles, in case runes went straight to pouch
        updateRunePouchDeltas("inventory");
    }

    // Helper to apply a single positive delta to the panel by item id
    private boolean applyDeltaToPanel(int itemId, int qty, String source)
    {
        if (qty <= 0)
        {
            return false;
        }

        final PanelItemData row = panelById.get(itemId);
        if (row == null)
        {
            // Fallback: try to locate by scanning the list once (should not normally happen)
            for (PanelItemData p : runeTracker)
            {
                if (p.getId() == itemId)
                {
                    panelById.put(itemId, p);
                    // continue with this row
                    return applyDeltaToPanel(itemId, qty, source);
                }
            }
            log.debug("applyDeltaToPanel({}): itemId {} is not a tracked rune", source, itemId);
            return false;
        }

        final int oldCrafted = row.getCrafted();
        final int newCrafted = oldCrafted + qty;
        row.setCrafted(newCrafted);
        row.setVisible(true);

        // Update price if we can; ignore failures gracefully
        try
        {
            if (manager != null)
            {
                int price = manager.getItemPrice(itemId);
                if (price > 0)
                {
                    row.setCostPerRune(price);
                }
            }
        }
        catch (Exception ex)
        {
            log.debug("Failed to update item price for {} via manager: {}", itemId, ex.getMessage());
        }

        if (log.isDebugEnabled())
        {
            log.debug("Applied delta to panel ({}): id={} +{} -> crafted={} (price={})",
                source, itemId, qty, newCrafted, row.getCostPerRune());
        }
        return true;
    }

    // Take a fresh snapshot of the current inventory into inventorySnapshot
    private void takeInventorySnapshot()
    {
        if (client == null)
        {
            inventorySnapshot = HashMultiset.create();
            return;
        }

        final ItemContainer inv = client.getItemContainer(InventoryID.INV);
        final Multiset<Integer> snap = HashMultiset.create();
        if (inv != null)
        {
            Arrays.stream(inv.getItems())
                .filter(it -> it != null && it.getId() > 0 && it.getQuantity() > 0)
                .forEach(it -> snap.add(it.getId(), it.getQuantity()));
        }
        inventorySnapshot = snap;

        if (log.isDebugEnabled())
        {
            String rs = snap.entrySet().stream()
                .filter(e -> runeIdSet.contains(e.getElement()))
                .map(e -> e.getElement() + "x" + e.getCount())
                .collect(Collectors.joining(", "));
            log.debug("Inventory snapshot set; rune summary: [{}]", rs);
        }
    }

    // Enum names map 1:1 with runeIDs order for UI label purposes
    private enum Runes
    {
        AIR,
        MIND,
        WATER,
        EARTH,
        FIRE,
        BODY,
        COSMIC,
        CHAOS,
        ASTRAL,
        NATURE,
        LAW,
        DEATH,
        BLOOD,
        SOUL,
        WRATH,
        MIST,
        DUST,
        MUD,
        SMOKE,
        STEAM,
        LAVA
    }
}
