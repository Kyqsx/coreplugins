package br.com.coreplugins.spawners.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Formatação de números (1.5K, 2M...) usada nas mensagens e menus. */
public final class Amounts {

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Q", "QQ", "S", "SS", "OC", "N", "D"};

    private Amounts() {
    }

    /** 1500 -> 1.5K */
    public static String letter(double value) {
        double abs = Math.abs(value);
        int i = 0;
        while (abs >= 1000D && i < SUFFIXES.length - 1) {
            abs /= 1000D;
            i++;
        }
        return (value < 0 ? "-" : "") + plain(abs) + SUFFIXES[i];
    }

    /** 1500 -> 1.500 (padrão brasileiro) */
    public static String number(double value) {
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(new Locale("pt", "BR")));
        return df.format(value);
    }

    private static String plain(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        BigDecimal bd = new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) {
            bd = bd.setScale(0);
        }
        return bd.toPlainString();
    }
}
