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
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
	private static final String CONFIG_GROUP = "runecraftingtracker";
	// net.runelite.api.gameval.EnumID.RUNEPOUCH_RUNE — not yet exposed in the API
	private static final int ENUM_RUNEPOUCH_RUNE = 982;
	private static final String CONFIG_KEY_PREFIX = "crafted.";

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
	private boolean runecraftXpGainedThisTick;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Override
	protected void startUp() throws Exception
	{
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		uiPanel = new RunecraftingTrackerPanel(itemManager, runeTracker, this::resetAll);

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
		uiPanel = null;
	}

	private void refreshPanel()
	{
		// Snapshot prices here on the client thread — ItemManager.getItemPrice()
		// calls client.getItemDefinition() which asserts client-thread access.
		for (PanelItemData runeData : runeTracker)
		{
			runeData.setCostPerRune(itemManager.getItemPrice(runeData.getId()));
		}
		SwingUtilities.invokeLater(() ->
		{
			// May fire after shutDown(); skip if the panel is gone.
			if (uiPanel != null)
			{
				uiPanel.pack();
				uiPanel.refresh();
			}
		});
	}

	private void init()
	{
		for (Runes rune : Runes.values())
		{
			Integer savedCount = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + rune.name(), int.class);
			int count = savedCount != null ? savedCount : 0;
			PanelItemData data = new PanelItemData(
				rune.name(),
				rune.getItemId(),
				count > 0,
				count,
				0);
			runeTracker.add(data);
			runeTrackerMap.put(rune.getItemId(), data);
		}
	}

	private void resetAll()
	{
		// Mutate state on the client thread (same thread as updateRuneTracker) to
		// avoid a race between a concurrent craft tick and the EDT reset action.
		clientThread.invokeLater(() ->
		{
			for (PanelItemData runeData : runeTracker)
			{
				runeData.setCrafted(0);
				runeData.setVisible(false);
				configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + runeData.getName());
			}
			refreshPanel();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGING_IN)
		{
			if (runeTracker.isEmpty())
			{
				init();
				refreshPanel();
			}
			// Null snapshots so the first GameTick after login re-primes the baseline
			// without attributing pre-existing runes as crafted.
			inventorySnapshot = null;
			runePouchSnapshot = null;
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

		runecraftXpGainedThisTick = true;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final ItemContainer itemContainer = client.getItemContainer(InventoryID.INV);
		Multiset<Integer> currentInventory = itemContainer != null
			? createInventorySnapshot(itemContainer)
			: HashMultiset.create();
		Multiset<Integer> currentRunePouch = createRunePouchSnapshot();

		// First tick after login: prime the baselines and do nothing else.
		if (inventorySnapshot == null || runePouchSnapshot == null)
		{
			inventorySnapshot = currentInventory;
			runePouchSnapshot = currentRunePouch;
			runecraftXpGainedThisTick = false;
			return;
		}

		if (runecraftXpGainedThisTick)
		{
			Multiset<Integer> diff = HashMultiset.create();
			addPositiveDiff(diff, currentInventory, inventorySnapshot);
			addPositiveDiff(diff, currentRunePouch, runePouchSnapshot);

			if (!diff.isEmpty())
			{
				updateRuneTracker(diff);
			}
		}

		inventorySnapshot = currentInventory;
		runePouchSnapshot = currentRunePouch;
		runecraftXpGainedThisTick = false;
	}

	private void addPositiveDiff(Multiset<Integer> diff, Multiset<Integer> current, Multiset<Integer> snapshot)
	{
		for (Integer itemId : current.elementSet())
		{
			int delta = current.count(itemId) - snapshot.count(itemId);
			if (delta > 0)
			{
				diff.add(itemId, delta);
			}
		}
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
				configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + runeData.getName(), runeData.getCrafted());
			}
		}

		refreshPanel();
	}

	private Multiset<Integer> createRunePouchSnapshot()
	{
		Multiset<Integer> snapshot = HashMultiset.create();
		final EnumComposition runepouchEnum = client.getEnum(ENUM_RUNEPOUCH_RUNE);

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

		int[] regions = client.getTopLevelWorldView().getMapRegions();
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
}
