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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.LinkedList;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

public class RunecraftingTrackerPanel extends PluginPanel
{
	private static final int PANEL_BORDER_TOP = 10;
	private static final int PANEL_BORDER_SIDES = 5;
	private static final int ITEM_BORDER_VERTICAL = 4;
	private static final int ITEM_BORDER_HORIZONTAL = 10;
	private static final int TEXT_BORDER = 5;
	private static final int TOP_PANEL_BORDER = 5;
	private static final int GRID_SPACING = 2;

	private static final String HTML_LABEL_TEMPLATE =
			"<html><body style='color:%s'>%s<span style='color:white'>%s</span></body></html>";

	// When there is nothing tracked, display this
	private final PluginErrorPanel errorPanel = new PluginErrorPanel();
	private final JPanel layoutContainer;
	private ItemManager itemManager;
	private LinkedList<PanelItemData> runeTracker;

	private final ImageIcon COIN_ICON =
			new ImageIcon(ImageUtil.getResourceStreamFromClass(RunecraftingTrackerPlugin.class,"COIN.png"));


	RunecraftingTrackerPanel(ItemManager itemManager, LinkedList<PanelItemData> runeTracker)
	{

		this.itemManager = itemManager;
		this.runeTracker = runeTracker;

		setBorder(new EmptyBorder(PANEL_BORDER_TOP, PANEL_BORDER_SIDES, PANEL_BORDER_SIDES, PANEL_BORDER_SIDES));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		layoutContainer = new JPanel();
		layoutContainer.setLayout(new GridLayout(0, 1, 0, GRID_SPACING));

		add(layoutContainer, BorderLayout.NORTH);

		// Error panel
		errorPanel.setContent("Runecrafting Tracker", "You have not crafted any runes yet.");

		pack();
	}

		protected void pack()
	{
		layoutContainer.removeAll();

		long totalProfit = runeTracker.stream()
			.mapToLong(runeData -> (long) runeData.getCrafted() * runeData.getCostPerRune())
			.sum();

		if (runeTracker.size() == 0)
		{
			layoutContainer.add(errorPanel);
		} else {
			layoutContainer.add(topPanelItem(COIN_ICON, totalProfit));

			runeTracker.forEach((runeData) -> {
				if (runeData.isVisible())
				{
					JPanel runePanelItem = runePanelItem(
							runeData.getId(),
							runeData.getCrafted(),
							(long) runeData.getCrafted() * runeData.getCostPerRune());
					layoutContainer.add(runePanelItem);
				}
			});
		}

	}

	protected void refresh()
	{
		revalidate();
	}

	protected LinkedList<PanelItemData> getRuneTracker()
	{
		return runeTracker;
	}

	private static String createLabel(String label, long value)
	{
		return createLabel(label, value, "");
	}

	private static String createLabel(String label, long value, String valueSuffix)
	{
		final String valueStr = QuantityFormatter.quantityToStackSize(value);
		return String.format(HTML_LABEL_TEMPLATE, ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR), label, valueStr + valueSuffix);
	}

	private JPanel runePanelItem(int itemId, int craftedCount, long profitAmount)
	{
		JPanel container = new JPanel();
		container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.setLayout(new BorderLayout());
		container.setBorder(new EmptyBorder(ITEM_BORDER_VERTICAL, ITEM_BORDER_HORIZONTAL, ITEM_BORDER_VERTICAL, ITEM_BORDER_HORIZONTAL));

		JLabel iconLabel = new JLabel();
		itemManager.getImage(itemId, craftedCount, true).addTo(iconLabel);
		container.add(iconLabel, BorderLayout.WEST);

		JPanel textContainer = new JPanel();
		textContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		textContainer.setLayout(new GridLayout(2, 1));
		textContainer.setBorder(new EmptyBorder(TEXT_BORDER, TEXT_BORDER, TEXT_BORDER, ITEM_BORDER_HORIZONTAL));

		JLabel topLine = new JLabel(createLabel("Crafted: ", craftedCount));
		topLine.setForeground(Color.WHITE);
		topLine.setFont(FontManager.getRunescapeSmallFont());

		JLabel bottomLine = new JLabel(createLabel("Profit: ", profitAmount, " gp"));
		bottomLine.setForeground(Color.WHITE);
		bottomLine.setFont(FontManager.getRunescapeSmallFont());

		textContainer.add(topLine);
		textContainer.add(bottomLine);

		container.add(textContainer, BorderLayout.CENTER);

		return container;
	}

	private JPanel topPanelItem(ImageIcon icon, long totalProfit)
	{
		JPanel panelContainer = new JPanel();
		panelContainer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		panelContainer.setLayout(new BorderLayout());
		panelContainer.setBorder(new EmptyBorder(TOP_PANEL_BORDER, ITEM_BORDER_HORIZONTAL, TOP_PANEL_BORDER, ITEM_BORDER_HORIZONTAL));

		JLabel iconLabel = new JLabel(icon);
		panelContainer.add(iconLabel, BorderLayout.WEST);

		JLabel middleLine = new JLabel(createLabel("Total profit: ", totalProfit, " gp"));
		middleLine.setForeground(Color.WHITE);
		middleLine.setFont(FontManager.getRunescapeSmallFont());

		JPanel textContainer = new JPanel();
		textContainer.setBackground(ColorScheme.SCROLL_TRACK_COLOR);
		textContainer.setLayout(new GridLayout(1, 1));
		textContainer.setBorder(new EmptyBorder(0, ITEM_BORDER_HORIZONTAL, 0, ITEM_BORDER_HORIZONTAL));
		textContainer.add(middleLine);

		panelContainer.add(textContainer, BorderLayout.CENTER);

		final JMenuItem resetAll = new JMenuItem("Reset All");

		resetAll.addActionListener(e ->
		{
			final int result = JOptionPane.showOptionDialog(panelContainer, String.format("<html>This will permanently delete <b>all</b> crafted runes.</html>"),
					"Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
					null, new String[]{"Yes", "No"}, "No");

			if (result != JOptionPane.YES_OPTION)
			{
				return;
			}


			for (PanelItemData runeData : runeTracker)
			{
				runeData.setCrafted(0);
				runeData.setVisible(false);
			}

			layoutContainer.removeAll();
			layoutContainer.add(errorPanel);

		});

		final JPopupMenu popupMenu = new JPopupMenu();
		popupMenu.setBorder(new EmptyBorder(TOP_PANEL_BORDER, TOP_PANEL_BORDER, TOP_PANEL_BORDER, TOP_PANEL_BORDER));
		popupMenu.add(resetAll);
		panelContainer.setComponentPopupMenu(popupMenu);

		return panelContainer;
	}
}
