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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
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
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == null || event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int animId = event.getActor().getAnimation();
		if (animId == RUNECRAFTING_ANIMATION_ID)
		{
			takeInventorySnapshot();
		}
		else
		{
			inventorySnapshot = null;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
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

	protected LinkedList<PanelItemData> getRuneTracker()
	{
		return runeTracker;
	}

	enum Runes
	{
		AIR(556),
		MIND(558),
		WATER(555),
		EARTH(557),
		FIRE(554),
		BODY(559),
		COSMIC(564),
		CHAOS(562),
		ASTRAL(9075),
		NATURE(561),
		LAW(563),
		DEATH(560),
		BLOOD(565),
		SOUL(566),
		WRATH(21880),
		MIST(4695),
		DUST(4696),
		MUD(4698),
		SMOKE(4697),
		STEAM(4694),
		LAVA(4699),
		AETHER(30887);

		private final int itemId;

		Runes(int itemId)
		{
			this.itemId = itemId;
		}

		public int getItemId()
		{
			return itemId;
		}
	}
}
