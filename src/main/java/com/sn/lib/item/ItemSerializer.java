package com.sn.lib.item;

import java.nio.ByteBuffer;
import java.util.Base64;

import org.bukkit.inventory.ItemStack;

/**
 * Binary item stack serialization that survives over-stacked amounts.
 *
 * <p>{@link ItemStack#serializeAsBytes()} clamps the amount to the material's max stack
 * size, silently losing over-stacked amounts (gotcha SnLootBoxes). The real amount is
 * therefore written as a 4-byte big-endian prefix and the body is serialized with amount
 * 1, so {@link #deserialize} restores the exact original amount.</p>
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    /**
     * Serializes a stack to bytes: 4-byte amount prefix plus the Paper byte form of the
     * single-amount copy.
     *
     * @throws IllegalArgumentException on null or air stacks, which have no byte form
     */
    public static byte[] serialize(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            throw new IllegalArgumentException("No se puede serializar un item null o AIR");
        }
        ItemStack single = stack.clone();
        single.setAmount(1);
        byte[] body = single.serializeAsBytes();
        ByteBuffer out = ByteBuffer.allocate(4 + body.length);
        out.putInt(stack.getAmount());
        out.put(body);
        return out.array();
    }

    /**
     * Restores a stack from {@link #serialize} output, reapplying the real amount even
     * when it exceeds the material's max stack size.
     */
    public static ItemStack deserialize(byte[] data) {
        if (data == null || data.length <= 4) {
            throw new IllegalArgumentException("Datos de item invalidos ("
                    + (data == null ? "null" : data.length + " bytes") + ")");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        int amount = in.getInt();
        byte[] body = new byte[data.length - 4];
        in.get(body);
        ItemStack stack = ItemStack.deserializeBytes(body);
        stack.setAmount(Math.max(1, amount));
        return stack;
    }

    /** Base64 form of {@link #serialize}, for text storage (yml, database columns). */
    public static String serializeBase64(ItemStack stack) {
        return Base64.getEncoder().encodeToString(serialize(stack));
    }

    /** Inverse of {@link #serializeBase64}. */
    public static ItemStack deserializeBase64(String data) {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("Datos de item base64 vacios");
        }
        return deserialize(Base64.getDecoder().decode(data));
    }
}
