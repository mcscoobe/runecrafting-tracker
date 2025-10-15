package com.runecraftingtracker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralizes all Rune Pouch related logic: detection and contents reading.
 */
@Slf4j
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

    // Canonical mapping from pouch type index (1-based; 0 = empty) to rune item IDs
    private static final int[] INDEX_TO_ITEM_ID = new int[] {
        ItemID.AIRRUNE,    // 1 Air
        ItemID.WATERRUNE,  // 2 Water
        ItemID.EARTHRUNE,  // 3 Earth
        ItemID.FIRERUNE,   // 4 Fire
        ItemID.MINDRUNE,   // 5 Mind
        ItemID.CHAOSRUNE,  // 6 Chaos
        ItemID.DEATHRUNE,  // 7 Death
        ItemID.BLOODRUNE,  // 8 Blood
        ItemID.BODYRUNE,   // 9 Body
        ItemID.NATURERUNE, // 10 Nature
        ItemID.LAWRUNE,    // 11 Law
        ItemID.COSMICRUNE, // 12 Cosmic
        ItemID.SOULRUNE,   // 13 Soul
        ItemID.ASTRALRUNE, // 14 Astral
        ItemID.MISTRUNE,   // 15 Mist
        ItemID.DUSTRUNE,   // 16 Dust
        ItemID.MUDRUNE,    // 17 Mud
        ItemID.SMOKERUNE,  // 18 Smoke
        ItemID.STEAMRUNE,  // 19 Steam
        ItemID.LAVARUNE,   // 20 Lava
        ItemID.WRATHRUNE   // 21 Wrath
    };

    // Human-friendly names aligned with INDEX_TO_ITEM_ID order
    private static final String[] RUNE_NAMES = new String[] {
        "Air", "Water", "Earth", "Fire", "Mind", "Chaos", "Death", "Blood", "Body",
        "Nature", "Law", "Cosmic", "Soul", "Astral", "Mist", "Mud", "Dust", "Smoke",
        "Steam", "Lava", "Wrath"
    };

    // itemId -> display name map
    private static final Map<Integer, String> ITEM_NAME_LOOKUP;
    static {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i < INDEX_TO_ITEM_ID.length; i++)
        {
            m.put(INDEX_TO_ITEM_ID[i], RUNE_NAMES[i]);
        }
        ITEM_NAME_LOOKUP = Collections.unmodifiableMap(m);
    }

    // Known rune pouch item ids (inventory variants)
    private static final Set<Integer> POUCH_IDS = new HashSet<>(Arrays.asList(
        ItemID.DIVINE_RUNE_POUCH,
        ItemID.DIVINE_RUNE_POUCH_TROUVER,
        ItemID.BH_RUNE_POUCH,
        ItemID.BH_RUNE_POUCH_TROUVER
    ));

    private final Client client;

    @Inject
    public RunePouchManager(Client client)
    {
        this.client = client;
    }

    /**
     * @return true if no rune pouch variant is present. Detect via varbits first, then inventory container as fallback.
     */
    public boolean hasNoRunePouch()
    {
        // Check varbits first: if any slot has a type or qty > 0, a pouch is effectively present
        boolean anyType = false;
        boolean anyQty = false;
        for (int i = 0; i < RUNE_VAR_BITS.length; i++)
        {
            int t = client.getVarbitValue(RUNE_VAR_BITS[i]);
            int q = client.getVarbitValue(AMOUNT_VAR_BITS[i]);
            anyType |= t > 0;
            anyQty |= q > 0;
        }
        if (anyType || anyQty)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Rune pouch detected via varbits: {}", varbitSummary());
            }
            return false;
        }

        // Fallback: look for a pouch item in the main inventory
        if (containerHasPouch(InventoryID.INV))
        {
            if (log.isDebugEnabled())
            {
                log.debug("Rune pouch detected via inventory items");
            }
            return false;
        }

        if (log.isDebugEnabled())
        {
            log.debug("No rune pouch detected (varbits empty and no pouch item in inventory)");
        }
        return true;
    }

    private String varbitSummary()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RUNE_VAR_BITS.length; i++)
        {
            int t = client.getVarbitValue(RUNE_VAR_BITS[i]);
            int q = client.getVarbitValue(AMOUNT_VAR_BITS[i]);
            if (i > 0) sb.append(", ");
            sb.append("[t=").append(t).append(",q=").append(q).append("]");
        }
        return sb.toString();
    }

    private boolean containerHasPouch(int containerId)
    {
        final ItemContainer c = client.getItemContainer(containerId);
        if (c == null)
        {
            return false;
        }
        for (Item it : c.getItems())
        {
            if (it == null)
            {
                continue;
            }
            final int id = it.getId();
            if (POUCH_IDS.contains(id))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean loggedKeyMappingOnce = false;

    /**
     * Reads rune contents from client varbits. Works for both regular (3 slots) and Divine (4 slots) pouches.
     * Supports clients where type varbits store either an index (1..21) or the actual item id (>= 500).
     * @return LinkedHashMap of rune itemId -> quantity (preserves slot order)
     */
    public Map<Integer, Integer> readContents()
    {
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        if (client == null || hasNoRunePouch())
        {
            return out;
        }

        if (!loggedKeyMappingOnce && log.isDebugEnabled())
        {
            // Log critical mapping range to confirm correctness in user logs
            int[] idx = {9,10,11,12,13,14};
            String m = Arrays.stream(idx)
                .mapToObj(i -> i + "->" + INDEX_TO_ITEM_ID[i-1])
                .collect(Collectors.joining(", "));
            log.debug("Rune pouch index mapping (9..14): {}", m);
            loggedKeyMappingOnce = true;
        }

        final int slots = RUNE_VAR_BITS.length;
        for (int i = 0; i < slots; i++)
        {
            final int runeTypeVal = client.getVarbitValue(RUNE_VAR_BITS[i]);
            final int qty = client.getVarbitValue(AMOUNT_VAR_BITS[i]);

            if (qty <= 0 || runeTypeVal <= 0)
            {
                continue; // empty slot
            }

            final int itemId;
            if (runeTypeVal >= 500)
            {
                // Some client builds expose the actual item id directly in the varbit
                itemId = runeTypeVal;
            }
            else
            {
                // 1-based index into our mapping
                final int idx1 = runeTypeVal - 1;
                if (idx1 < 0 || idx1 >= INDEX_TO_ITEM_ID.length)
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug("Rune pouch: unknown index {} in slot {} (qty={})", runeTypeVal, i + 1, qty);
                    }
                    continue;
                }
                itemId = INDEX_TO_ITEM_ID[idx1];
            }

            if (log.isDebugEnabled())
            {
                log.debug("Rune pouch slot {}: type={} qty={} -> itemId={}", i + 1, runeTypeVal, qty, itemId);
            }

            out.merge(itemId, qty, Integer::sum);
        }

        if (log.isDebugEnabled())
        {
            String contents = out.entrySet().stream()
                .map(e -> ITEM_NAME_LOOKUP.getOrDefault(e.getKey(), String.valueOf(e.getKey())) + " x" + e.getValue())
                .collect(Collectors.joining(", "));
            log.debug("Rune pouch read: {}", contents.isEmpty() ? "(empty)" : contents);
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

    /**
     * @return true if the given varbit id corresponds to rune pouch type or quantity varbits.
     */
    public boolean isRunePouchVarBit(int varBitId)
    {
        for (int id : RUNE_VAR_BITS)
        {
            if (id == varBitId)
            {
                return true;
            }
        }
        for (int id : AMOUNT_VAR_BITS)
        {
            if (id == varBitId)
            {
                return true;
            }
        }
        return false;
    }
}
