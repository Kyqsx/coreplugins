package br.com.coreplugins.economias.menu;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.economy.Currency;
import br.com.coreplugins.economias.util.Amounts;
import br.com.coreplugins.economias.util.Yml;
import br.com.coreplugins.economias.util.YmlEdit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Menu de configuração das economias (/economies). Edita economies/<id>.yml preservando os comentários
 * (YmlEdit), valida o resultado e recarrega. Valores de texto/número são digitados no chat.
 * <p>
 * Compatível da 1.8 em diante de propósito: não usa InventoryView, nem materiais renomeados na 1.13.
 */
public final class MenuManager implements Listener {

    private static final long PROMPT_TTL_MS = 60000L;
    /** Economias por página: miolo do inventário (7 colunas x 4 linhas), dentro da moldura. */
    private static final int PAGE_SIZE = 28;
    private static final int[] ECON_SLOTS = buildEconSlots();

    private static int[] buildEconSlots() {
        int[] slots = new int[PAGE_SIZE];
        int n = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[n++] = row * 9 + col;
            }
        }
        return slots;
    }

    private static int economyIndex(int slot) {
        for (int i = 0; i < ECON_SLOTS.length; i++) {
            if (ECON_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private enum Kind { MAIN, EDIT }

    private static final class Holder implements InventoryHolder {
        final Kind kind;
        final String currency;
        final int page;
        Inventory inventory;

        Holder(Kind kind, String currency, int page) {
            this.kind = kind;
            this.currency = currency;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Prompt {
        final String field;
        final String currency;
        final long expires;

        Prompt(String field, String currency) {
            this.field = field;
            this.currency = currency;
            this.expires = System.currentTimeMillis() + PROMPT_TTL_MS;
        }
    }

    private final EconomiasProduct plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<UUID, Prompt>();

    public MenuManager(EconomiasProduct plugin) {
        this.plugin = plugin;
    }

    // ---------- abertura ----------

    public void openMain(Player p, int page) {
        List<Currency> list = new ArrayList<Currency>(plugin.getCurrencies().all());
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder(Kind.MAIN, null, page);
        Inventory inv = Bukkit.createInventory(h, 54, color("&8&lEconomias &7(" + (page + 1) + "/" + pages + ")"));
        h.inventory = inv;
        frame(inv);
        String vault = plugin.getConfig().getString("vault-currency", "");
        inv.setItem(4, item(Material.NETHER_STAR, "&b&lCoreEconomias",
                "&7Gerencie as moedas do servidor."));
        if (list.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "&cNenhuma economia",
                    "&7Use &fNova economia &7para criar a primeira."));
        }
        for (int i = 0; i < PAGE_SIZE && page * PAGE_SIZE + i < list.size(); i++) {
            Currency c = list.get(page * PAGE_SIZE + i);
            boolean isVault = c.getId().equalsIgnoreCase(vault);
            List<String> lore = new ArrayList<String>();
            lore.add("&7Comando: &f/" + c.getCommandName());
            lore.add("&7Formato: &f" + c.getFormat().name());
            lore.add("&7Saldo inicial: &f" + c.format(c.getDefaultBalance()));
            lore.add("&7Pagamentos: " + (c.isPayEnabled() ? "&a&lligado" : "&c&ldesligado"));
            if (isVault) {
                lore.add("");
                lore.add("&bEconomia do Vault");
            }
            lore.add("");
            lore.add("&e» Clique para configurar");
            inv.setItem(ECON_SLOTS[i], item(isVault ? Material.PAPER : Material.PAPER,
                    "&6&l" + c.getId().toUpperCase(Locale.ROOT), lore.toArray(new String[0])));
        }
        if (page > 0) {
            inv.setItem(45, item(Material.ARROW, "&e« Página anterior"));
        }
        inv.setItem(47, item(Material.ANVIL, "&a&lNova economia", "&7Cria uma economia a partir do modelo.", "", "&e» Clique e digite o nome no chat"));
        inv.setItem(49, item(Material.BARRIER, "&c&lFechar"));
        inv.setItem(51, item(Material.HOPPER, "&b&lRecarregar", "&7Relê as economias dos arquivos .yml."));
        if (page < pages - 1) {
            inv.setItem(53, item(Material.ARROW, "&ePróxima página »"));
        }
        p.openInventory(inv);
    }

    public void openEdit(Player p, String id) {
        Currency c = plugin.getCurrencies().get(id);
        if (c == null) {
            openMain(p, 0);
            return;
        }
        Holder h = new Holder(Kind.EDIT, c.getId(), 0);
        String shortId = c.getId().length() > 18 ? c.getId().substring(0, 18) : c.getId();
        Inventory inv = Bukkit.createInventory(h, 36, color("&8&lEditar: &f&l" + shortId.toUpperCase(Locale.ROOT)));
        h.inventory = inv;
        frame(inv);
        boolean vault = c.getId().equalsIgnoreCase(plugin.getConfig().getString("vault-currency", ""));
        String click = "&e» Clique para alterar";
        inv.setItem(4, item(vault ? Material.PAPER : Material.PAPER, "&6&l" + c.getId().toUpperCase(Locale.ROOT),
                "&7Comando: &f/" + c.getCommandName(),
                vault ? "&b&lEconomia do Vault" : "&7Edite as opções abaixo."));
        inv.setItem(10, item(Material.NAME_TAG, "&6Nome (singular)", "&7Atual: &f" + c.getSingular(), "", click));
        inv.setItem(11, item(Material.NAME_TAG, "&6Nome (plural)", "&7Atual: &f" + c.getPlural(), "", click));
        inv.setItem(12, item(Material.PAPER, "&6Formato dos valores",
                "&7Atual: &f" + c.getFormat().name(),
                "&7Exemplo: &f" + (c.getFormat() == Currency.Format.LETTER ? "1.5K, 2M" : "1.500, 2.000.000"),
                "", "&e» Clique para alternar"));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6Saldo inicial", "&7Atual: &f" + c.format(c.getDefaultBalance()),
                "&7Para quem entra pela primeira vez.", "", click));
        inv.setItem(14, item(Material.COMPASS, "&6Comando", "&7Atual: &f/" + c.getCommandName(), "", click));
        inv.setItem(15, item(Material.BOOK, "&6Apelidos do comando",
                "&7Atual: &f" + (c.getAliases().isEmpty() ? "Nenhum" : join(c.getAliases())), "", click));
        inv.setItem(16, item(Material.IRON_INGOT, "&6Intervalo entre usos", "&7Atual: &f" + c.getCommandDelay() + "s",
                "&7Deixe 0 para sem limite.", "", click));
        inv.setItem(19, toggle("&aPagamentos entre jogadores", c.isPayEnabled(), "&7Liga/desliga o /" + c.getCommandName() + " pagar."));
        inv.setItem(20, item(Material.GOLD_NUGGET, "&aValor mínimo por pagamento", "&7Atual: &f" + c.format(c.getMinPay()), "", click));
        inv.setItem(21, toggle("&aBloquear pagar offline", c.isBlockOfflinePay(), "&7Se ligado, só paga jogadores online."));
        inv.setItem(22, item(Material.CHEST, "&eTamanho do TOP", "&7Atual: &f" + c.getTopSize(), "&7De 1 a 50 posições.", "", click));
        inv.setItem(23, item(Material.DIAMOND, "&bEconomia do Vault",
                vault ? "&aEsta é a economia do Vault." : "&7Não é a economia do Vault.",
                "&7Troca exige reiniciar o servidor.", "", vault ? "&8" : "&e» Clique para usar esta"));
        inv.setItem(31, item(Material.ARROW, "&e« Voltar"));
        p.openInventory(inv);
    }

    // ---------- cliques ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        final Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || top.getItem(slot) == null) {
            return;
        }
        if (!canUse(p)) {
            p.closeInventory();
            plugin.getMessages().send(p, "permission");
            return;
        }
        Holder h = (Holder) top.getHolder();
        try {
            if (h.kind == Kind.MAIN) {
                clickMain(p, h, slot);
            } else {
                clickEdit(p, h, slot);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Erro no menu de economias", ex);
            p.sendMessage(color("&cErro interno no menu. Veja o console."));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    private void clickMain(final Player p, Holder h, int slot) {
        if (slot == 45) {
            if (h.page > 0) {
                later(p, h.page - 1, null);
            }
        } else if (slot == 53) {
            int pages = Math.max(1, (plugin.getCurrencies().all().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (h.page < pages - 1) {
                later(p, h.page + 1, null);
            }
        } else if (slot == 49) {
            p.closeInventory();
        } else if (slot == 51) {
            plugin.getCurrencies().reload();
            p.sendMessage(color("&aArquivos recarregados."));
            later(p, h.page, null);
        } else if (slot == 47) {
            askChat(p, "new", null, "o nome da nova economia (letras minúsculas, números e _; até 24)");
        } else {
            int idx = economyIndex(slot);
            if (idx >= 0) {
                List<Currency> list = new ArrayList<Currency>(plugin.getCurrencies().all());
                int index = h.page * PAGE_SIZE + idx;
                if (index < list.size()) {
                    later(p, -1, list.get(index).getId());
                }
            }
        }
    }

    private void clickEdit(final Player p, Holder h, int slot) {
        final String id = h.currency;
        Currency c = plugin.getCurrencies().get(id);
        if (c == null) {
            later(p, 0, null);
            return;
        }
        switch (slot) {
            case 10:
                askChat(p, "singular", id, "o nome no singular (ex.: coin)");
                break;
            case 11:
                askChat(p, "plural", id, "o nome no plural (ex.: coins)");
                break;
            case 12:
                save(p, id, "format", c.getFormat() == Currency.Format.LETTER ? "NUMBER" : "LETTER");
                break;
            case 13:
                askChat(p, "default", id, "o saldo inicial (ex.: 0, 1000, 2.5k)");
                break;
            case 14:
                askChat(p, "command", id, "o nome do comando, sem a barra (ex.: coins)");
                break;
            case 15:
                askChat(p, "aliases", id, "os apelidos separados por vírgula (ex.: moedas, money) ou 'nenhum'");
                break;
            case 16:
                askChat(p, "delay", id, "os segundos entre usos (0 a 3600)");
                break;
            case 19:
                save(p, id, "pay.enabled", String.valueOf(!c.isPayEnabled()));
                break;
            case 20:
                askChat(p, "minpay", id, "o valor mínimo por pagamento (ex.: 1, 500, 1k)");
                break;
            case 21:
                save(p, id, "pay.block-offline", String.valueOf(!c.isBlockOfflinePay()));
                break;
            case 22:
                askChat(p, "top", id, "o tamanho do TOP (1 a 50)");
                break;
            case 23:
                setVault(p, id);
                break;
            case 31:
                later(p, 0, null);
                break;
            default:
                break;
        }
    }

    // ---------- digitação no chat ----------

    private void askChat(Player p, String field, String currency, String what) {
        prompts.put(p.getUniqueId(), new Prompt(field, currency));
        p.closeInventory();
        p.sendMessage(color("&eDigite no chat " + what + "."));
        p.sendMessage(color("&7Digite &fcancelar &7para desistir (expira em 60s)."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        Prompt prompt = prompts.get(p.getUniqueId());
        if (prompt == null) {
            return;
        }
        if (System.currentTimeMillis() > prompt.expires) {
            prompts.remove(p.getUniqueId());
            return; // expirou: a mensagem segue como chat normal
        }
        e.setCancelled(true);
        final String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                handlePrompt(p, text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }

    private void handlePrompt(Player p, String text) {
        Prompt pr = prompts.remove(p.getUniqueId());
        if (pr == null || !p.isOnline()) {
            return;
        }
        if (!canUse(p)) {
            plugin.getMessages().send(p, "permission");
            return;
        }
        if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
            p.sendMessage(color("&7Cancelado."));
            later(p, 0, pr.currency);
            return;
        }
        String id = pr.currency;
        String error = null;
        try {
            if (pr.field.equals("new")) {
                error = createEconomy(p, text);
                return;
            }
            error = applyField(p, pr, text);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Erro ao aplicar a edição do menu", ex);
            error = "Erro interno. Veja o console.";
        } finally {
            if (error != null) {
                p.sendMessage(color("&c" + error));
                if (id != null) {
                    later(p, -1, id);
                } else {
                    later(p, 0, null);
                }
            }
        }
    }

    /** Valida o texto digitado e salva. Devolve a mensagem de erro, ou null se deu certo. */
    private String applyField(Player p, Prompt pr, String text) {
        String id = pr.currency;
        switch (pr.field) {
            case "singular":
            case "plural": {
                if (text.isEmpty() || text.length() > 32) {
                    return "O nome deve ter de 1 a 32 caracteres.";
                }
                return save(p, id, "name." + pr.field, YmlEdit.quote(text)) ? null : "Não foi possível salvar.";
            }
            case "default": {
                double v = Amounts.parse(text);
                if (Double.isNaN(v) || v < 0D || Double.isInfinite(v)) {
                    return "Quantia inválida. Exemplos: 0, 1000, 2.5k.";
                }
                return save(p, id, "default", plain(v)) ? null : "Não foi possível salvar.";
            }
            case "minpay": {
                double v = Amounts.parse(text);
                if (Double.isNaN(v) || v < 0D || Double.isInfinite(v)) {
                    return "Quantia inválida. Exemplos: 1, 500, 1k.";
                }
                return save(p, id, "pay.min", plain(v)) ? null : "Não foi possível salvar.";
            }
            case "delay": {
                Integer n = parseInt(text, 0, 3600);
                if (n == null) {
                    return "Digite um número inteiro de 0 a 3600.";
                }
                return save(p, id, "command-delay", String.valueOf(n)) ? null : "Não foi possível salvar.";
            }
            case "top": {
                Integer n = parseInt(text, 1, 50);
                if (n == null) {
                    return "Digite um número inteiro de 1 a 50.";
                }
                return save(p, id, "top.size", String.valueOf(n)) ? null : "Não foi possível salvar.";
            }
            case "command": {
                String name = text.toLowerCase(Locale.ROOT);
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (!name.matches("[a-z0-9_-]{1,32}")) {
                    return "Comando inválido: use letras minúsculas, números, - e _ (até 32).";
                }
                if (plugin.getCurrencies().commandNameTaken(name, id)) {
                    return "Já existe um comando chamado /" + name + ".";
                }
                return save(p, id, "command.name", YmlEdit.quote(name)) ? null : "Não foi possível salvar.";
            }
            case "aliases": {
                List<String> aliases = new ArrayList<String>();
                if (!text.equalsIgnoreCase("nenhum") && !text.equalsIgnoreCase("none")) {
                    for (String part : text.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
                        String a = part.startsWith("/") ? part.substring(1) : part;
                        if (a.isEmpty()) {
                            continue;
                        }
                        if (!a.matches("[a-z0-9_-]{1,32}")) {
                            return "Apelido inválido: '" + a + "'.";
                        }
                        if (plugin.getCurrencies().commandNameTaken(a, id)) {
                            return "Já existe um comando chamado /" + a + ".";
                        }
                        if (!aliases.contains(a)) {
                            aliases.add(a);
                        }
                    }
                }
                if (aliases.size() > 8) {
                    return "No máximo 8 apelidos.";
                }
                return save(p, id, "command.aliases", YmlEdit.list(aliases)) ? null : "Não foi possível salvar.";
            }
            default:
                return "Campo desconhecido.";
        }
    }

    // ---------- salvar / criar ----------

    /** Edita um valor do arquivo da economia, valida, recarrega e reabre o menu de edição. */
    private boolean save(Player p, String id, String path, String value) {
        File f = new File(plugin.getDataFolder(), "economies/" + id + ".yml");
        byte[] before = null;
        try {
            before = Files.readAllBytes(f.toPath());
            Files.write(new File(f.getPath() + ".bak").toPath(), before); // desfazer: volta o .bak
            Map<String, String> change = new LinkedHashMap<String, String>();
            change.put(path, value);
            YmlEdit.apply(f, change);
            new Currency(id, Yml.load(f)); // valida: se o arquivo ficou inválido, desfaz abaixo
        } catch (Exception ex) {
            restore(f, before);
            plugin.getLogger().log(Level.WARNING, "Edição de " + id + "." + path + " desfeita: " + ex.getMessage());
            p.sendMessage(color("&cNão foi possível salvar (alteração desfeita): " + ex.getMessage()));
            later(p, -1, id);
            return false;
        }
        plugin.getCurrencies().reload();
        later(p, -1, id);
        return true;
    }

    private void restore(File f, byte[] before) {
        if (before == null) {
            return;
        }
        try {
            Files.write(f.toPath(), before);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Não foi possível restaurar " + f.getName(), e);
        }
    }

    private void setVault(Player p, String id) {
        File f = new File(plugin.getDataFolder(), "config.yml");
        try {
            Map<String, String> change = new LinkedHashMap<String, String>();
            change.put("vault-currency", YmlEdit.quote(id));
            YmlEdit.apply(f, change);
            plugin.reloadConfig();
        } catch (Exception ex) {
            p.sendMessage(color("&cNão foi possível salvar o config.yml: " + ex.getMessage()));
            return;
        }
        p.sendMessage(color("&aA economia do Vault agora é &f" + id + "&a. &7Reinicie o servidor para aplicar."));
        later(p, -1, id);
    }

    /** Cria economies/<id>.yml a partir do modelo. Devolve erro ou null. */
    private String createEconomy(Player p, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{1,24}")) {
            return "Nome inválido: use letras minúsculas, números e _ (até 24).";
        }
        if (plugin.getCurrencies().get(id) != null) {
            return "Já existe uma economia chamada " + id + ".";
        }
        if (plugin.getCurrencies().commandNameTaken(id, id)) {
            return "Já existe um comando chamado /" + id + "; escolha outro nome.";
        }
        File dir = new File(plugin.getDataFolder(), "economies");
        File f = new File(dir, id + ".yml");
        if (f.exists()) {
            return "O arquivo economies/" + id + ".yml já existe.";
        }
        try {
            String template = readTemplate();
            template = template.replace("coins", id).replace("COINS", id.toUpperCase(Locale.ROOT));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "Não foi possível criar a pasta economies/.";
            }
            Files.write(f.toPath(), template.getBytes(StandardCharsets.UTF_8));
            Map<String, String> change = new LinkedHashMap<String, String>();
            change.put("name.singular", YmlEdit.quote(id));
            change.put("name.plural", YmlEdit.quote(id));
            change.put("command.name", YmlEdit.quote(id));
            change.put("command.aliases", "[]");
            change.put("default", "0");
            YmlEdit.apply(f, change);
            new Currency(id, Yml.load(f));
        } catch (Exception ex) {
            if (f.exists() && !f.delete()) {
                plugin.getLogger().warning("Não foi possível apagar " + f.getName() + " após falha.");
            }
            plugin.getLogger().log(Level.WARNING, "Falha ao criar a economia " + id, ex);
            return "Não foi possível criar a economia: " + ex.getMessage();
        }
        plugin.getCurrencies().reload();
        p.sendMessage(color("&aEconomia &f" + id + " &acriada. Configure-a abaixo."));
        later(p, -1, id);
        return null;
    }

    private String readTemplate() throws IOException {
        InputStream in = plugin.getResource("economies/coins.yml");
        if (in == null) {
            throw new IOException("modelo economies/coins.yml ausente no plugin");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ---------- util ----------

    /** Reabre um menu no próximo tick (trocar o inventário dentro do evento de clique não é seguro). */
    private void later(final Player p, final int page, final String editId) {
        Bukkit.getScheduler().runTask(plugin.getLoader(), new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || !canUse(p)) {
                    return;
                }
                if (editId != null) {
                    openEdit(p, editId);
                } else {
                    openMain(p, page);
                }
            }
        });
    }

    private static boolean canUse(Player p) {
        return p.hasPermission("coreeconomias.menu") || p.hasPermission("coreeconomias.admin");
    }

    private static ItemStack toggle(String name, boolean on, String description) {
        return item(on ? Material.EMERALD : Material.REDSTONE, name,
                "&7Estado: " + (on ? "&a&lLIGADO" : "&c&lDESLIGADO"), description, "", "&e» Clique para alternar");
    }

    /** Moldura: borda preta e miolo cinza (vidro colorido, resolvido pelo nome para funcionar da 1.8 à atual). */
    private static void frame(Inventory inv) {
        ItemStack dark = pane(15, "BLACK_STAINED_GLASS_PANE");
        ItemStack soft = pane(7, "GRAY_STAINED_GLASS_PANE");
        int size = inv.getSize();
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
            inv.setItem(i, edge ? dark : soft);
        }
    }

    private static ItemStack pane(int legacyData, String modernName) {
        ItemStack it;
        Material m = Material.getMaterial(modernName);
        if (m != null) {
            it = new ItemStack(m);
        } else {
            m = Material.getMaterial("STAINED_GLASS_PANE"); // 1.8 a 1.12: a cor vai no "data"
            if (m == null) {
                return null;
            }
            it = new ItemStack(m, 1, (short) legacyData);
        }
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> lines = new ArrayList<String>();
            for (String l : lore) {
                lines.add(color(l));
            }
            meta.setLore(lines);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static Integer parseInt(String s, int min, int max) {
        try {
            int n = Integer.parseInt(s.trim());
            return n < min || n > max ? null : Integer.valueOf(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Número sem notação científica (YAML e leitura humana). */
    private static String plain(double v) {
        String s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        return s.equals("") ? "0" : s;
    }
}
