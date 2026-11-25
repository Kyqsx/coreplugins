package br.com.coreplugins.economias.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formatação (1K, 1M...) e leitura de quantias digitadas pelo jogador. */
public final class Amounts {

    private static volatile String[] suffixes =
            {"", "K", "M", "B", "T", "Q", "QQ", "S", "SS", "OC", "N", "D"};

    private Amounts() {
    }

    public static void setSuffixes(List<String> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<String> all = new ArrayList<String>();
        all.add("");
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                all.add(s.trim());
            }
        }
        suffixes = all.toArray(new String[0]);
    }

    /** 1500 -> 1.5K */
    public static String letter(double value) {
        String[] sfx = suffixes;
        double abs = Math.abs(value);
        int i = 0;
        while (abs >= 1000D && i < sfx.length - 1) {
            abs /= 1000D;
            i++;
        }
        return (value < 0 ? "-" : "") + plain(abs, 2) + sfx[i];
    }

    /** 1500 -> 1.500 (padrão brasileiro) */
    public static String number(double value) {
        DecimalFormat df = new DecimalFormat("#,##0.##",
                DecimalFormatSymbols.getInstance(new Locale("pt", "BR")));
        return df.format(value);
    }

    /** Número sem notação científica, para placeholders "brutos". */
    public static String raw(double value) {
        return plain(value, 2);
    }

    private static String plain(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        BigDecimal bd = new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) {
            bd = bd.setScale(0);
        }
        return bd.toPlainString();
    }

    /**
     * Lê "100", "2.5k", "1,5M". Devolve NaN se for inválido.
     * Pontos de milhar (1.000) não são aceitos: o ponto é sempre decimal.
     */
    public static double parse(String input) {
        if (input == null) {
            return Double.NaN;
        }
        String s = input.trim().replace(',', '.');
        if (s.isEmpty()) {
            return Double.NaN;
        }
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return Double.NaN;
        }
        double base;
        try {
            base = Double.parseDouble(s.substring(0, end));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        double multiplier = 1D;
        String tail = s.substring(end);
        if (!tail.isEmpty()) {
            String[] sfx = suffixes;
            int idx = -1;
            for (int i = 1; i < sfx.length; i++) {
                if (sfx[i].equalsIgnoreCase(tail)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return Double.NaN;
            }
            multiplier = Math.pow(1000D, idx);
        }
        double result = base * multiplier;
        return (Double.isNaN(result) || Double.isInfinite(result)) ? Double.NaN : result;
    }
}
