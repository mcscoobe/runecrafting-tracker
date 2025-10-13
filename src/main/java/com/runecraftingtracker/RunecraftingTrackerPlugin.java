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
import com.google.common.collect.Multisets;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
        name = "Runecrafting Tracker",
        description = "Track your total profit and the amount of runes you have crafted",
        tags = {"rc", "rune", "craft", "runecraft", "runecrafting", "track", "tracker", "zmi", "ourania", "altar"}
)
public class RunecraftingTrackerPlugin extends Plugin
{
    private static final int RUNECRAFTING_ANIMATION_ID = 791;

    private RunecraftingTrackerPanel uiPanel;

    private final int[] runeIDs = {556, 558, 555, 557, 554, 559, 564, 562, 9075, 561, 563, 560, 565, 566, 21880, 4695, 4696, 4698, 4697, 4694, 4699};
    private final int[] regionIds = new int[]{1111, 2222};
    private NavigationButton uiNavigationButton;
    private LinkedList<PanelItemData> runeTracker = new LinkedList<>();
    private Multiset<Integer> inventorySnapshot;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager manager;

    @Override
    protected void startUp() throws Exception
    {
        final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "icon.png");
        uiPanel = new RunecraftingTrackerPanel(manager, runeTracker);

        uiNavigationButton = NavigationButton.builder()
                .tooltip("Runecrafting Tracker")
                .icon(icon)
                .priority(10)
                .panel(uiPanel)
                .build();

        clientToolbar.addNavigation(uiNavigationButton);
    }

    @Override
    protected void shutDown() throws Exception
    {
        clientToolbar.removeNavigation(uiNavigationButton);
    }

    private void init()
    {
        for (int i = 0; i < Runes.values().length; i++)
        {
            runeTracker.add(new PanelItemData(
                    Runes.values()[i].name(),
                    runeIDs[i],
                    false,
                    0,
                    manager.getItemPrice(runeIDs[i])));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGING_IN)
        {
            if (runeTracker.isEmpty()) {
                clientThread.invokeLater(this::init);
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null ||
                event.getSkill() != Skill.RUNECRAFT)
        {
            return;
        }
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        processChange(inventory);
    }

    private void processChange(ItemContainer current)
    {
        // Create inventory multiset {id -> quantity}
        Multiset<Integer> currentInventory = HashMultiset.create();
        Arrays.stream(current.getItems())
                .forEach(item -> currentInventory.add(item.getId(), item.getQuantity()));

        // Get inventory diff with snapshot
        final Multiset<Integer> diff = Multisets.difference(currentInventory, inventorySnapshot);

        // Convert multiset diff to ItemStack list
        List<ItemStack> items = diff.entrySet().stream()
                .map(e -> new ItemStack(e.getElement(), e.getCount(), client.getLocalPlayer().getLocalLocation()))
                .collect(Collectors.toList());

        LinkedList<PanelItemData> panels = uiPanel.getRuneTracker();

        if (items.size() > 0) {
            for (ItemStack stack : items)
            {
                for (PanelItemData item : panels)
                {
                    if (item.getId() == stack.getId())
                    {
                        if (!item.isVisible()) {
                            item.setVisible(true);
                        }
                        item.setCrafted(item.getCrafted() + stack.getQuantity());
                    }
                }
            }
            inventorySnapshot = currentInventory;

            try
            {
                SwingUtilities.invokeAndWait(uiPanel::pack);
            }
            catch (InterruptedException | InvocationTargetException e)
            {
                e.printStackTrace();
            }

            uiPanel.refresh();
        }
    }

    private void takeInventorySnapshot()
    {
        final ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);
        if (itemContainer != null)
        {
            inventorySnapshot = HashMultiset.create();
            Arrays.stream(itemContainer.getItems())
                    .forEach(item -> inventorySnapshot.add(item.getId(), item.getQuantity()));
        }
    }

    protected LinkedList<PanelItemData> getRuneTracker()
    {
        return runeTracker;
    }

    enum Runes
    {AIR, MIND, WATER, EARTH, FIRE, BODY, COSMIC, CHAOS, ASTRAL, NATURE, LAW, DEATH, BLOOD, SOUL, WRATH, MIST, DUST, MUD, SMOKE, STEAM, LAVA}
}
