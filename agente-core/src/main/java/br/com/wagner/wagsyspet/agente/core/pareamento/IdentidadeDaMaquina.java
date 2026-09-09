package br.com.wagner.wagsyspet.agente.core.pareamento;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * O que o agente diz de si no {@code POST /parear} (vai para o painel "Caixas pareados" e para o log do backend), já nos
 * limites do {@code PararAgenteRequest}: {@code so} ≤ 20, {@code arch} ≤ 20, {@code hostname} ≤ 120, sem caracteres de
 * controle (CR/LF forjariam linha de log). Só o {@code so} é normalizado ({@code windows|linux|macos}) — é o que o
 * painel do dono rotula.
 */
public record IdentidadeDaMaquina(String so, String arch, String hostname) {

    public static IdentidadeDaMaquina destaMaquina() {
        return new IdentidadeDaMaquina(
                normalizarSo(System.getProperty("os.name")),
                normalizarArch(System.getProperty("os.arch")),
                hostnameDe(System.getenv(), IdentidadeDaMaquina::hostnameDaRede));
    }

    private static String hostnameDaRede() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    static String normalizarSo(String osName) {
        if (osName == null || osName.isBlank()) {
            return "desconhecido";
        }
        String s = osName.toLowerCase(Locale.ROOT);
        if (s.contains("win")) {
            return "windows";
        }
        if (s.contains("mac") || s.contains("darwin")) {
            return "macos";
        }
        if (s.contains("linux")) {
            return "linux";
        }
        return limitar(semControle(s), 20);
    }

    static String normalizarArch(String osArch) {
        if (osArch == null || osArch.isBlank()) {
            return null;
        }
        String a = osArch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> limitar(semControle(a), 20);
        };
    }

    /** {@code COMPUTERNAME} (Windows) → {@code HOSTNAME} (Unix) → fallback (ex.: {@code InetAddress}); tudo em try/catch. */
    static String hostnameDe(Map<String, String> env, Supplier<String> fallback) {
        String h = primeiroNaoBranco(env.get("COMPUTERNAME"), env.get("HOSTNAME"));
        if (h == null) {
            try {
                h = fallback.get();
            } catch (Exception e) {
                h = null;
            }
        }
        if (h == null) {
            return null;
        }
        String limpo = limitar(semControle(h.trim()), 120);
        return limpo.isEmpty() ? null : limpo;
    }

    private static String primeiroNaoBranco(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String semControle(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().filter(cp -> Character.getType(cp) != Character.CONTROL).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String limitar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
