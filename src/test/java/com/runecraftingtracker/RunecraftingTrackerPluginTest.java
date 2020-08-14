package com.runecraftingtracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RunecraftingTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RunecraftingTrackerPlugin.class);
		RuneLite.main(args);
	}
}