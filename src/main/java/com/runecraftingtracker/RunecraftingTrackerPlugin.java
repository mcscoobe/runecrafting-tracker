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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

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
	// Rune pouch VarBits for tracking contents
	private static final int[] RUNE_POUCH_AMOUNT_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};

	private RunecraftingTrackerPanel uiPanel;
	private NavigationButton uiNavigationButton;
	private final LinkedList<PanelItemData> runeTracker = new LinkedList<>();
	private final Map<Integer, PanelItemData> runeTrackerMap = new HashMap<>();
	private Multiset<Integer> inventorySnapshot;
	private Multiset<Integer> runePouchSnapshot;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Override
	protected void startUp() throws Exception
	{
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		uiPanel = new RunecraftingTrackerPanel(itemManager, runeTracker);

		uiNavigationButton = NavigationButton.builder()
			.tooltip("Runecrafting Tracker")
			.icon(icon)
			.priority(10)
			.panel(uiPanel)
			.build();

		clientToolbar.addNavigation(uiNavigationButton);

		// Prime an initial snapshot so the first craft can be detected
        // Don't take rune pouch snapshot on startup to avoid counting existing runes as crafted
        clientThread.invokeLater(this::takeInventorySnapshot);
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(uiNavigationButton);
	}

	private void init()
	{
		for (Runes rune : Runes.values())
		{
			PanelItemData data = new PanelItemData(
				rune.name(),
				rune.getItemId(),
				false,
				0,
				itemManager.getItemPrice(rune.getItemId()));
			runeTracker.add(data);
			runeTrackerMap.put(rune.getItemId(), data);
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
		if (event.getSkill() != Skill.RUNECRAFT)
		{
			return;
		}

		if (isNotInRunecraftingRegion())
		{
			return;
		}

		// Process both inventory and rune pouch changes after XP gain
		final ItemContainer itemContainer = client.getItemContainer(InventoryID.INV);
		if (itemContainer != null)
		{
			processChange(itemContainer);
		}
		
		// Process rune pouch changes with a small delay to ensure VarBits are updated
		clientThread.invokeLater(this::processRunePouchChange);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Remove automatic inventory processing - only process after XP gains
		// This prevents bank withdrawals from being counted as "crafted"
		return;
	}

	private void processChange(ItemContainer current)
	{
		// Take initial snapshot if needed
		if (inventorySnapshot == null)
		{
			inventorySnapshot = createInventorySnapshot(current);
			return; // Don't process changes on first snapshot
		}

		// Create inventory multiset {id -> quantity}
		Multiset<Integer> currentInventory = createInventorySnapshot(current);

		// Calculate difference manually to avoid @Beta API
		Multiset<Integer> diff = HashMultiset.create();
		for (Integer itemId : currentInventory.elementSet())
		{
			int currentCount = currentInventory.count(itemId);
			int snapshotCount = inventorySnapshot.count(itemId);
			int difference = currentCount - snapshotCount;
			if (difference > 0)
			{
				diff.add(itemId, difference);
			}
		}

		if (!diff.isEmpty()) {
			updateRuneTracker(diff);
		}
		inventorySnapshot = currentInventory;
	}

	private void processRunePouchChange()
	{
		// Take initial snapshot if needed
		if (runePouchSnapshot == null)
		{
			runePouchSnapshot = createRunePouchSnapshot();
			return; // Don't process changes on first snapshot
		}

		Multiset<Integer> currentRunePouch = createRunePouchSnapshot();

		// Calculate difference
		Multiset<Integer> diff = HashMultiset.create();
		for (Integer itemId : currentRunePouch.elementSet())
		{
			int currentCount = currentRunePouch.count(itemId);
			int snapshotCount = runePouchSnapshot.count(itemId);
			int difference = currentCount - snapshotCount;
			if (difference > 0)
			{
				diff.add(itemId, difference);
			}
		}

		if (!diff.isEmpty()) {
			updateRuneTracker(diff);
		}
		runePouchSnapshot = currentRunePouch;
	}

	private void updateRuneTracker(Multiset<Integer> diff)
	{
		for (Multiset.Entry<Integer> entry : diff.entrySet())
		{
			PanelItemData runeData = runeTrackerMap.get(entry.getElement());
			if (runeData != null)
			{
				if (!runeData.isVisible())
				{
					runeData.setVisible(true);
				}
				runeData.setCrafted(runeData.getCrafted() + entry.getCount());
			}
		}

		SwingUtilities.invokeLater(() -> {
			uiPanel.pack();
			uiPanel.refresh();
		});
	}

	private void takeInventorySnapshot()
	{
		final ItemContainer itemContainer = client.getItemContainer(InventoryID.INV);
		if (itemContainer != null)
		{
			inventorySnapshot = createInventorySnapshot(itemContainer);
		}
	}

	private void takeRunePouchSnapshot()
	{
		runePouchSnapshot = createRunePouchSnapshot();
	}

	private Multiset<Integer> createRunePouchSnapshot()
	{
		Multiset<Integer> snapshot = HashMultiset.create();
		final EnumComposition runepouchEnum = client.getEnum(982); // EnumID.RUNEPOUCH_RUNE
		
		if (runepouchEnum == null)
		{
			return snapshot;
		}

		for (int i = 0; i < RUNE_POUCH_AMOUNT_VARBITS.length; i++)
		{
			int amount = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
			int runeType = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
			
			if (runeType != 0 && amount > 0)
			{
				// Convert rune pouch type ID to actual item ID
				int itemId = runepouchEnum.getIntValue(runeType);
				if (itemId != -1)
				{
					snapshot.add(itemId, amount);
				}
			}
		}
		
		return snapshot;
	}

	private Multiset<Integer> createInventorySnapshot(ItemContainer container)
	{
		Multiset<Integer> snapshot = HashMultiset.create();
		Arrays.stream(container.getItems())
			.forEach(item -> snapshot.add(item.getId(), item.getQuantity()));
		return snapshot;
	}

	private boolean isNotInRunecraftingRegion()
	{
		if (client.getLocalPlayer() == null)
		{
			return true;
		}

		int[] regions = client.getMapRegions();
		if (regions == null)
		{
			return true;
		}

		for (int region : regions)
		{
			if (RunecraftingRegions.REGIONS.contains(region))
			{
				return false;
			}
		}

		return true;
	}

	protected LinkedList<PanelItemData> getRuneTracker()
	{
		return runeTracker;
	}
}