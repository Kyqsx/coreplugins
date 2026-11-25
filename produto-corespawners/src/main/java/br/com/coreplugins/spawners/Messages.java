package br.com.coreplugins.spawners;

import br.com.coreplugins.spawners.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** messages.yml: chaves com ponto (ex.: block.placed). Textos com várias linhas são enviados linha a linha. */
public final class Messages {

    private final SpawnersProduct plugin;
    private YamlConfiguration cfg;
    private YamlConfiguration defaults;

    public Messages(SpawnersProduct plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        // Chaves que o arquivo do servidor ainda não tem (versões novas do plugin) usam o texto padrão do jar.
        defaults = Defaults.load(plugin, "messages.yml");
        if (defaults != null) {
            cfg.setDefaults(defaults);
        }
    }

    public String raw(String key) {
        String s = cfg.getString(key);
        return s == null ? "&c[mensagem ausente: " + key + "]" : s;
    }

    public String text(String key, Map<String, String> vars) {
        return Text.render(raw(key), vars);
    }

    public String text(String key) {
        return text(key, Collections.<String, String>emptyMap());
    }

    public void send(CommandSender to, String key, Map<String, String> vars) {
        if (to == null) {
            return;
        }
        List<String> lines = cfg.isList(key) ? cfg.getStringList(key) : null;
        if (lines != null) {
            for (String l : lines) {
                to.sendMessage(Text.render(l, vars));
            }
            return;
        }
        String s = text(key, vars);
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) {
                to.sendMessage(line);
            }
        }
    }

    public void send(CommandSender to, String key) {
        send(to, key, Collections.<String, String>emptyMap());
    }

    public String on(boolean value) {
        return text(value ? "words.on" : "words.off");
    }
}
