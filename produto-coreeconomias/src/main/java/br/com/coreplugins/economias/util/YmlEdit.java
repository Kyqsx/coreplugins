package br.com.coreplugins.economias.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edita valores simples de um YAML preservando comentários e a ordem do arquivo.
 * (O YamlConfiguration.save() do Bukkit apagaria os comentários que documentam os arquivos de economia.)
 * Só mexe em valores escalares ou listas em linha ({@code [a, b]}) na própria linha da chave.
 */
public final class YmlEdit {

    private YmlEdit() {
    }

    public static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public static String list(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(items.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Aplica todas as alterações (caminho com pontos -> valor YAML já formatado) e grava o arquivo uma vez. */
    public static void apply(File file, Map<String, String> changes) throws IOException {
        List<String> lines = new ArrayList<String>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : changes.entrySet()) {
            set(lines, e.getKey(), e.getValue());
        }
        File tmp = new File(file.getPath() + ".tmp");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void set(List<String> lines, String path, String value) {
        String[] parts = path.split("\\.");
        List<String> keys = new ArrayList<String>();
        List<Integer> indents = new ArrayList<Integer>();
        int deepestHeader = -1;
        int deepestDepth = 0;
        int headerIndent = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) {
                continue;
            }
            int colon = keyColon(t);
            if (colon < 0) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            String keyText = t.substring(0, colon);
            String key = unquote(keyText.trim());
            String rest = t.substring(colon + 1).trim();
            boolean header = rest.isEmpty() || rest.startsWith("#");
            String current = join(keys, key);
            if (current.equals(path)) {
                if (header) {
                    throw new IllegalStateException("'" + path + "' é uma seção ou lista em bloco; edite o arquivo à mão.");
                }
                String comment = trailingComment(rest);
                lines.set(i, line.substring(0, indent) + keyText + ": " + value + (comment == null ? "" : "  " + comment));
                return;
            }
            if (header) {
                if (path.startsWith(current + ".") && keys.size() + 1 > deepestDepth) {
                    deepestHeader = i;
                    deepestDepth = keys.size() + 1;
                    headerIndent = indent;
                }
                keys.add(key);
                indents.add(indent);
            }
        }
        // Não existe: cria sob o trecho do caminho que já existe (ou no fim do arquivo).
        List<String> add = new ArrayList<String>();
        int base = deepestHeader >= 0 ? headerIndent + 2 : 0;
        for (int k = deepestDepth; k < parts.length; k++) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < base + 2 * (k - deepestDepth); s++) {
                sb.append(' ');
            }
            sb.append(parts[k]).append(':');
            if (k == parts.length - 1) {
                sb.append(' ').append(value);
            }
            add.add(sb.toString());
        }
        if (deepestHeader >= 0) {
            lines.addAll(deepestHeader + 1, add);
        } else {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.add("");
            }
            lines.addAll(add);
        }
    }

    private static int keyColon(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String join(List<String> keys, String last) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(k).append('.');
        }
        return sb.append(last).toString();
    }

    /** Comentário no fim da linha ("# ..." fora de aspas), ou null. */
    private static String trailingComment(String rest) {
        char quote = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && (i == 0 || rest.charAt(i - 1) == ' ')) {
                return rest.substring(i);
            }
        }
        return null;
    }
}
