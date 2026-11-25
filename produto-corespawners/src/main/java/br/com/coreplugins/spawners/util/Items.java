package br.com.coreplugins.spawners.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Criação de itens a partir dos .yml (nomes de materiais novos e antigos, cabeças) e a marca invisível
 * que identifica o item de gerador (sem depender de NBT, então vale da 1.8 às versões atuais).
 */
public final class Items {

    private static final String MARK = "\u00a7r\u00a7r\u00a7r";
    private static final String MAGIC = "CS2;";
    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        alias("SPAWNER", "MOB_SPAWNER");
        alias("GUNPOWDER", "SULPHUR");
        alias("EXPERIENCE_BOTTLE", "EXP_BOTTLE");
        alias("ENDER_EYE", "EYE_OF_ENDER");
        alias("PLAYER_HEAD", "SKULL_ITEM");
        alias("REDSTONE_TORCH", "REDSTONE_TORCH_ON");
        alias("BEEF", "RAW_BEEF");
        alias("COOKED_BEEF", "COOKED_BEEF");
        alias("PORKCHOP", "PORK");
        alias("COOKED_PORKCHOP", "GRILLED_PORK");
        alias("CHICKEN", "RAW_CHICKEN");
        alias("MUTTON", "RAW_MUTTON");
        alias("GOLDEN_SWORD", "GOLD_SWORD");
        alias("CLOCK", "WATCH");
        alias("WRITABLE_BOOK", "BOOK_AND_QUILL");
        alias("GLASS_PANE", "THIN_GLASS");
        alias("SLIME_BALL", "SLIME_BALL");
    }

    private Items() {
    }

    private static void alias(String modern, String legacy) {
        ALIASES.put(modern, legacy);
        ALIASES.put(legacy, modern);
    }

    // ------------------------------------------------------------------ materiais

    /** Material pelo nome, aceitando o nome novo ou o antigo. {@code null} se não existir. */
    public static Material material(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        Material m = Material.getMaterial(n);
        if (m != null) {
            return m;
        }
        String other = ALIASES.get(n);
        return other == null ? null : Material.getMaterial(other);
    }

    /**
     * Interpreta o texto de um material: {@code NOME}, {@code NOME:dado}, {@code head:jogador} ou
     * {@code texture:hash}. Material desconhecido vira PAPEL, para o erro ficar visível no menu.
     */
    public static ItemStack parse(String spec, int amount) {
        int qty = Math.max(1, amount);
        if (spec == null || spec.trim().isEmpty()) {
            return new ItemStack(Material.PAPER, qty);
        }
        String s = spec.trim();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("head:")) {
            return playerHead(s.substring(5), qty);
        }
        if (low.startsWith("texture:")) {
            return textureHead(s.substring(8), qty);
        }
        if (s.matches("[0-9a-fA-F]{50,70}")) {
            return textureHead(s, qty);
        }
        short data = 0;
        String name = s;
        int i = s.indexOf(':');
        if (i > 0) {
            name = s.substring(0, i);
            try {
                data = Short.parseShort(s.substring(i + 1).trim());
            } catch (NumberFormatException ignored) {
                data = 0;
            }
        }
        if (name.equalsIgnoreCase("PLAYER_HEAD") || name.equalsIgnoreCase("SKULL_ITEM")) {
            return skullBase(qty);
        }
        Material m = material(name);
        if (m == null) {
            return new ItemStack(Material.PAPER, qty);
        }
        if (data != 0) {
            return new ItemStack(m, qty, data);
        }
        return new ItemStack(m, qty);
    }

    // ------------------------------------------------------------------ cabeças

    private static ItemStack skullBase(int amount) {
        Material modern = Material.getMaterial("PLAYER_HEAD");
        if (modern != null) {
            return new ItemStack(modern, amount);
        }
        Material legacy = Material.getMaterial("SKULL_ITEM");
        return new ItemStack(legacy == null ? Material.PAPER : legacy, amount, (short) 3);
    }

    @SuppressWarnings("deprecation")
    public static ItemStack playerHead(String playerName, int amount) {
        ItemStack it = skullBase(amount);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta && playerName != null && !playerName.isEmpty()) {
            try {
                ((SkullMeta) meta).setOwner(playerName);
                it.setItemMeta(meta);
            } catch (Throwable ignored) {
                // fica a cabeça padrão
            }
        }
        return it;
    }

    /** Cabeça com textura (hash da skin no servidor da Mojang). Se a versão não permitir, devolve a cabeça padrão. */
    public static ItemStack textureHead(String hash, int amount) {
        ItemStack it = skullBase(amount);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta) {
            try {
                Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
                Class<?> prop = Class.forName("com.mojang.authlib.properties.Property");
                Object profile = gp.getConstructor(UUID.class, String.class)
                        .newInstance(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)), "cs");
                Object props = gp.getMethod("getProperties").invoke(profile);
                String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
                String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
                Object property = prop.getConstructor(String.class, String.class).newInstance("textures", b64);
                props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", property);
                Field f = meta.getClass().getDeclaredField("profile");
                f.setAccessible(true);
                f.set(meta, profile);
                it.setItemMeta(meta);
            } catch (Throwable ignored) {
                // versão sem suporte a textura: cabeça padrão
            }
        }
        return it;
    }

    /** Faz o item de gerador já vir com o mob dentro da gaiola (o "ovo"). Ignora se a versão não permitir. */
    public static void spawnerType(ItemStack it, org.bukkit.entity.EntityType type) {
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta) {
                org.bukkit.inventory.meta.BlockStateMeta bsm = (org.bukkit.inventory.meta.BlockStateMeta) meta;
                org.bukkit.block.BlockState st = bsm.getBlockState();
                if (st instanceof org.bukkit.block.CreatureSpawner) {
                    ((org.bukkit.block.CreatureSpawner) st).setSpawnedType(type);
                    bsm.setBlockState(st);
                    it.setItemMeta(bsm);
                }
            }
        } catch (Throwable ignored) {
            // sem suporte nesta versão: o visual é aplicado depois que o bloco é colocado
        }
    }

    // ------------------------------------------------------------------ itens dos menus

    /**
     * Item de uma seção do menus.yml ({@code material}, {@code name}, {@code lore}, {@code amount}, {@code glow}),
     * com os placeholders aplicados.
     */
    public static ItemStack build(ConfigurationSection sec, Map<String, String> vars) {
        if (sec == null) {
            return new ItemStack(Material.PAPER);
        }
        ItemStack it = parse(Text.apply(sec.getString("material", "PAPER"), vars), sec.getInt("amount", 1));
        decorate(it, sec.getString("name"), sec.getStringList("lore"), vars, sec.getBoolean("glow", false));
        return it;
    }

    /** Aplica nome e descrição (com placeholders e cores) num item que já existe. */
    public static void decorate(ItemStack it, String name, List<String> lore, Map<String, String> vars, boolean glow) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return;
        }
        if (name != null) {
            meta.setDisplayName(Text.render(name, vars));
        }
        if (lore != null) {
            meta.setLore(Text.render(lore, vars));
        }
        if (glow) {
            Enchantment glowEnchant = Enchantment.getByName("DURABILITY");
            if (glowEnchant != null) {
                meta.addEnchant(glowEnchant, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        it.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ marca nos itens (gerador, booster)

    private static final boolean PDC = detectPdc();

    private static boolean detectPdc() {
        try {
            Class.forName("org.bukkit.persistence.PersistentDataContainer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean pdcAvailable() {
        return PDC;
    }

    /**
     * Grava um dado no item. Em 1.14+ usa o PersistentDataContainer (sobrevive a qualquer plugin e a
     * reinícios). Em versões antigas cai para uma marca invisível no fim da última linha da descrição.
     */
    public static ItemStack withTag(org.bukkit.plugin.Plugin owner, ItemStack it, String key, String payload) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        if (PDC) {
            try {
                PdcTag.write(owner, meta, key, payload);
                it.setItemMeta(meta);
                return it;
            } catch (Throwable ignored) {
                // cai para a marca na descrição
            }
        }
        List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
        if (lore.isEmpty()) {
            lore.add("\u00a77");
        }
        int last = lore.size() - 1;
        lore.set(last, lore.get(last) + MARK + encode(MAGIC + key + "|" + payload));
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** Dado gravado por {@link #withTag}, ou {@code null} se o item não tem. */
    public static String readTag(org.bukkit.plugin.Plugin owner, ItemStack it, String key) {
        if (it == null || !it.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return null;
        }
        if (PDC) {
            try {
                String v = PdcTag.read(owner, meta, key);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // tenta a marca na descrição
            }
        }
        if (!meta.hasLore()) {
            return null;
        }
        String prefix = MAGIC + key + "|";
        for (String line : meta.getLore()) {
            int idx = line.lastIndexOf(MARK);
            if (idx < 0) {
                continue;
            }
            String decoded = decode(line.substring(idx + MARK.length()));
            if (decoded != null && decoded.startsWith(prefix)) {
                return decoded.substring(prefix.length());
            }
        }
        return null;
    }

    /** Marca o item como gerador: tipo, stack e se já vem com o ovo. */
    public static ItemStack withMark(org.bukkit.plugin.Plugin owner, ItemStack it, String typeId, int stack, boolean egg) {
        return withTag(owner, it, "spawner", typeId + ";" + stack + ";" + (egg ? 1 : 0));
    }

    /** {tipo, stack, ovo(0/1)} do item, ou {@code null} se não for um gerador do plugin. */
    public static String[] readMark(org.bukkit.plugin.Plugin owner, ItemStack it) {
        String v = readTag(owner, it, "spawner");
        if (v == null) {
            return null;
        }
        String[] parts = v.split(";");
        if (parts.length == 2) {
            return new String[]{parts[0], parts[1], "1"};
        }
        return parts.length == 3 ? parts : null;
    }

    private static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            String hex = String.format("%02x", b & 0xff);
            sb.append('\u00a7').append(hex.charAt(0)).append('\u00a7').append(hex.charAt(1));
        }
        return sb.toString();
    }

    private static String decode(String s) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i + 1 < s.length(); i += 2) {
            if (s.charAt(i) != '\u00a7') {
                return null;
            }
            char c = s.charAt(i + 1);
            if (Character.digit(c, 16) < 0) {
                return null;
            }
            hex.append(c);
        }
        if (hex.length() == 0 || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
