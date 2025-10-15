package com.runecraftingtracker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

/**
 * Centralizes all Rune Pouch related logic: detection and contents reading.
 */
@Singleton
public final class RunePouchManager
{
    private static final int[] RUNE_VAR_BITS = {
        VarbitID.RUNE_POUCH_TYPE_1,
        VarbitID.RUNE_POUCH_TYPE_2,
        VarbitID.RUNE_POUCH_TYPE_3,
        VarbitID.RUNE_POUCH_TYPE_4
    };

    private static final int[] AMOUNT_VAR_BITS = {
        VarbitID.RUNE_POUCH_QUANTITY_1,
        VarbitID.RUNE_POUCH_QUANTITY_2,
        VarbitID.RUNE_POUCH_QUANTITY_3,
        VarbitID.RUNE_POUCH_QUANTITY_4
    };

    // Mapping from rune index (as stored in the rune pouch varbits) to rune item ids.
    // Varbit values are typically 1-based (0 = empty). Keep order aligned to gameval constants.
    private static final int[] RUNE_INDEX_TO_ITEM_ID = new int[] {
        ItemID.AIRRUNE,   // 1
        ItemID.MINDRUNE,  // 2
        ItemID.WATERRUNE, // 3
        ItemID.EARTHRUNE, // 4
        ItemID.FIRERUNE,  // 5
        ItemID.BODYRUNE,  // 6
        ItemID.COSMICRUNE,// 7
        ItemID.CHAOSRUNE, // 8
        ItemID.ASTRALRUNE,// 9
        ItemID.NATURERUNE,// 10
        ItemID.LAWRUNE,   // 11
        ItemID.DEATHRUNE, // 12
        ItemID.BLOODRUNE, // 13
        ItemID.SOULRUNE,  // 14
        ItemID.WRATHRUNE, // 15
        ItemID.MISTRUNE,  // 16
        ItemID.DUSTRUNE,  // 17
        ItemID.MUDRUNE,   // 18
        ItemID.SMOKERUNE, // 19
        ItemID.STEAMRUNE, // 20
        ItemID.LAVARUNE,  // 21
        ItemID.AETHERRUNE // 22 (if present in this client)
    };

    // Human-friendly names aligned with RUNE_INDEX_TO_ITEM_ID order
    private static final String[] RUNE_NAMES = new String[] {
        "Air", "Mind", "Water", "Earth", "Fire", "Body", "Cosmic", "Chaos", "Astral",
        "Nature", "Law", "Death", "Blood", "Soul", "Wrath", "Mist", "Dust", "Mud",
        "Smoke", "Steam", "Lava", "Aether"
    };

    // itemId -> display name map
    private static final Map<Integer, String> ITEM_NAME_LOOKUP;
    static {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i < RUNE_INDEX_TO_ITEM_ID.length; i++)
        {
            m.put(RUNE_INDEX_TO_ITEM_ID[i], RUNE_NAMES[i]);
        }
        ITEM_NAME_LOOKUP = Collections.unmodifiableMap(m);
    }

    private final Client client;

    @Inject
    public RunePouchManager(Client client)
    {
        this.client = client;
    }

    /**
     * @return true if no rune pouch variant is present in the player's inventory.
     */
    public boolean hasNoRunePouch()
    {
        final ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null)
        {
            return true;
        }
        for (Item it : inv.getItems())
        {
            if (it == null)
            {
                continue;
            }
            final int id = it.getId();
            if (id == ItemID.BH_RUNE_POUCH
                || id == ItemID.BH_RUNE_POUCH_TROUVER
                || id == ItemID.DIVINE_RUNE_POUCH
                || id == ItemID.DIVINE_RUNE_POUCH_TROUVER)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads rune contents from client varbits. Works for both regular (3 slots) and Divine (4 slots) pouches.
     * @return LinkedHashMap of rune itemId -> quantity (preserves slot order)
     */
    public Map<Integer, Integer> readContents()
    {
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        if (client == null || hasNoRunePouch())
        {
            return out;
        }

        final int slots = RUNE_VAR_BITS.length;
        for (int i = 0; i < slots; i++)
        {
            final int runeTypeVal = client.getVarbitValue(RUNE_VAR_BITS[i]);
            final int qty = client.getVarbitValue(AMOUNT_VAR_BITS[i]);

            if (qty <= 0)
            {
                continue; // empty slot
            }

            // Varbit is typically 1-based (0 = empty). Translate to 0-based index into our mapping array.
            final int idx = runeTypeVal - 1;
            if (idx < 0 || idx >= RUNE_INDEX_TO_ITEM_ID.length)
            {
                continue; // unknown/empty
            }

            final int itemId = RUNE_INDEX_TO_ITEM_ID[idx];
            out.merge(itemId, qty, Integer::sum);
        }

        return out;
    }

    /**
     * Formats current rune pouch contents as a human-readable string, e.g.:
     * "Air x100, Nature x5". Returns "(empty)" if no runes, or "(no pouch)" if none present.
     */
    public String toReadableString()
    {
        if (client == null)
        {
            return "(no client)";
        }
        if (hasNoRunePouch())
        {
            return "(no pouch)";
        }
        Map<Integer, Integer> contents = readContents();
        if (contents.isEmpty())
        {
            return "(empty)";
        }
        return contents.entrySet().stream()
            .map(e -> ITEM_NAME_LOOKUP.getOrDefault(e.getKey(), String.valueOf(e.getKey())) + " x" + e.getValue())
            .collect(Collectors.joining(", "));
    }
}
