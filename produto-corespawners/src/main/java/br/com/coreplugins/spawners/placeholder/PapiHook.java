package br.com.coreplugins.spawners.placeholder;

import br.com.coreplugins.spawners.SpawnersProduct;

/**
 * Registro da expansão no PlaceholderAPI. Só esta classe (e a SpawnersExpansion) tocam nas classes do
 * PlaceholderAPI, então o plugin funciona normalmente sem ele instalado.
 */
public final class PapiHook {

    private static SpawnersExpansion expansion;

    private PapiHook() {
    }

    public static boolean register(SpawnersProduct plugin) {
        if (expansion != null) {
            return true;
        }
        SpawnersExpansion e = new SpawnersExpansion(plugin);
        if (e.register()) {
            expansion = e;
            return true;
        }
        return false;
    }

    public static void unregister() {
        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI já foi desligado
            }
            expansion = null;
        }
    }
}
