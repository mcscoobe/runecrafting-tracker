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

import lombok.Getter;
import net.runelite.api.gameval.ItemID;

@Getter
public enum Runes
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
