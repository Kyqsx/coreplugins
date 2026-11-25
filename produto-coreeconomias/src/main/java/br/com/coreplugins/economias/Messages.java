package br.com.coreplugins.economias;

import br.com.coreplugins.economias.util.Yml;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/** Mensagens gerais (messages.yml). As de cada economia ficam no arquivo da economia. */
public final class Messages {

    private final EconomiasProduct plugin;
    private YamlConfiguration yaml;

    public Messages(EconomiasProduct plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        yaml = Yml.load(new File(plugin.getDataFolder(), "messages.yml"));
    }

    /** Texto bruto (com &, linhas separadas por \n). */
    public String raw(String key) {
        if (yaml.isList(key)) {
            List<String> lines = yaml.getStringList(key);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            return sb.toString();
        }
        String v = yaml.getString(key);
        return v == null ? "&c[mensagem ausente: " + key + "]" : v;
    }

    public String get(String key) {
        return ChatColor.translateAlternateColorCodes('&', raw(key));
    }

    public void send(CommandSender to, String key, String... placeholders) {
        String text = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        if (text.isEmpty()) {
            return;
        }
        for (String line : text.split("\n")) {
            to.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }
}
