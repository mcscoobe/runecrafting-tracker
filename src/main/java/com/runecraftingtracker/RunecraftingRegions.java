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

import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class RunecraftingRegions
{
	// Whitelist of region IDs where runecrafting occurs (from actual game data)
	public static final Set<Integer> REGIONS = ImmutableSet.of(
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

	private RunecraftingRegions()
	{
		// Utility class
	}
}
