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
import com.google.common.collect.ImmutableSet;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
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

	// Whitelist of region IDs where runecrafting occurs (from actual game data)
	private static final Set<Integer> RUNECRAFTING_REGIONS = ImmutableSet.of(
		// Air Altar
		11082, 11083, 11084, 11338, 11339, 11340, 11594, 11595, 11596,
		// Water Altar
		10570, 10571, 10572, 10826, 10827, 10828,
		// Earth Altar
		10314, 10315, 10316,
		// Fire Altar
		10059, 10060,
		// Body Altar
		9802, 9803, 9804, 10058,
		// Cosmic Altar
		8266, 8267, 8268, 8522, 8523, 8524, 8778, 8779, 8780,
		// Chaos Altar
		9034, 9035, 9036, 9290, 9291, 9292,
		// Astral Altar
		8251, 8252, 8253, 8507, 8508, 8509,
		// Nature Altar
		9546, 9547, 9548,
		// Law Altar (shares regions with Body/Nature)
		// Death Altar
		6715,
		// Blood Altar (Zeah)
		12618, 12619, 12620, 12874, 12875, 12876, 13130, 13131, 13132,
		// Ourania altar
		12119
	);

	private RunecraftingTrackerPanel uiPanel;
	private NavigationButton uiNavigationButton;
	private LinkedList<PanelItemData> runeTracker = new LinkedList<>();
	private Map<Integer, PanelItemData> runeTrackerMap = new HashMap<>();
	private Multiset<Integer> inventorySnapshot;

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
		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "icon.png");
		uiPanel = new RunecraftingTrackerPanel(itemManager, runeTracker);

		uiNavigationButton = NavigationButton.builder()
			.tooltip("Runecrafting Tracker")
			.icon(icon)
			.priority(10)
			.panel(uiPanel)
			.build();

		clientToolbar.addNavigation(uiNavigationButton);

		// Prime an initial snapshot so the first craft can be detected
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
			if (runeTracker.size() == 0) {
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

		if (!isInRunecraftingRegion())
		{
			return;
		}

		// Only take snapshot if one doesn't exist yet (before crafting)
		// Don't overwrite the baseline after crafting or we can't detect the difference
		if (inventorySnapshot == null)
		{
			takeInventorySnapshot();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!isInRunecraftingRegion())
		{
			return;
		}

		if (event.getContainerId() != InventoryID.INVENTORY.getId())
		{
			return;
		}

		processChange(event.getItemContainer());
	}

	private void processChange(ItemContainer current)
	{
		if (inventorySnapshot != null)
		{
			// Create inventory multiset {id -> quantity}
			Multiset<Integer> currentInventory = createInventorySnapshot(current);

			// Get inventory diff with snapshot
			final Multiset<Integer> diff = Multisets.difference(currentInventory, inventorySnapshot);

			// Convert multiset diff to ItemStack list
			List<ItemStack> items = diff.entrySet().stream()
				.map(e -> new ItemStack(e.getElement(), e.getCount(), client.getLocalPlayer().getLocalLocation()))
				.collect(Collectors.toList());

			if (items.size() > 0) {
				for (ItemStack stack : items)
				{
					PanelItemData runeData = runeTrackerMap.get(stack.getId());
					if (runeData != null)
					{
						if (!runeData.isVisible())
						{
							runeData.setVisible(true);
						}
						runeData.setCrafted(runeData.getCrafted() + stack.getQuantity());
					}
				}
				inventorySnapshot = currentInventory;

				SwingUtilities.invokeLater(() -> {
					uiPanel.pack();
					uiPanel.refresh();
				});
			}
		}
	}

	private void takeInventorySnapshot()
	{
		final ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);
		if (itemContainer != null)
		{
			inventorySnapshot = createInventorySnapshot(itemContainer);
		}
	}

	private Multiset<Integer> createInventorySnapshot(ItemContainer container)
	{
		Multiset<Integer> snapshot = HashMultiset.create();
		Arrays.stream(container.getItems())
			.forEach(item -> snapshot.add(item.getId(), item.getQuantity()));
		return snapshot;
	}

	private boolean isInRunecraftingRegion()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}

		int[] regions = client.getMapRegions();
		if (regions == null)
		{
			return false;
		}

		for (int region : regions)
		{
			if (RUNECRAFTING_REGIONS.contains(region))
			{
				return true;
			}
		}

		return false;
	}

	protected LinkedList<PanelItemData> getRuneTracker()
	{
		return runeTracker;
	}

	@Getter
    enum Runes
	{
		AIR(ItemID.AIRRUNE),
		MIND(ItemID.MINDRUNE),
		WATER(ItemID.WATERRUNE),
		EARTH(ItemID.EARTHRUNE),
		FIRE(ItemID.FIRERUNE),
		BODY(ItemID.BODYRUNE),
		COSMIC(ItemID.COSMICRUNE),
		CHAOS(ItemID.CHAOSRUNE),
		ASTRAL(ItemID.ASTRALRUNE),
		NATURE(ItemID.NATURERUNE),
		LAW(ItemID.LAWRUNE),
		DEATH(ItemID.DEATHRUNE),
		BLOOD(ItemID.BLOODRUNE),
		SOUL(ItemID.SOULRUNE),
		WRATH(ItemID.WRATHRUNE),
		MIST(ItemID.MISTRUNE),
		DUST(ItemID.DUSTRUNE),
		MUD(ItemID.MUDRUNE),
		SMOKE(ItemID.SMOKERUNE),
		STEAM(ItemID.STEAMRUNE),
		LAVA(ItemID.LAVARUNE),
		AETHER(ItemID.AETHERRUNE);

		private final int itemId;

		Runes(int itemId)
		{
			this.itemId = itemId;
		}

    }
}
