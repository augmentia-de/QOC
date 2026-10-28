package de.augmentia.oc.engine.http.security;

import java.net.InetAddress;
import java.util.List;

/**
 * Shared helpers for trusted-proxy handling.
 *
 * <p>Identity headers supplied by a reverse proxy are only honoured when the
 * request originates from one of the configured CIDRs. Keeping the matching
 * logic in one place avoids the JAX-RS filter and the A2A transport drifting
 * apart.</p>
 */
public final class TrustedProxy {
    private TrustedProxy() {
    }

    /** Returns the first hop from an {@code X-Forwarded-For} header, or {@code null}. */
    public static String clientIp(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        String first = xForwardedFor.split(",")[0].trim();
        return first.isBlank() ? null : first;
    }

    /** Returns {@code true} when {@code ip} is covered by one of the trusted entries. */
    public static boolean isTrusted(List<String> trusted, String ip) {
        if (trusted == null || trusted.isEmpty() || ip == null || ip.isBlank()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null) {
                continue;
            }
            String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if ("*".equals(candidate) || ip.equals(candidate)) {
                return true;
            }
            if (candidate.contains("/") && isInCidr(ip, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);
            InetAddress ipAddr = InetAddress.getByName(ip);
            InetAddress netAddr = InetAddress.getByName(network);
            byte[] ipBytes = ipAddr.getAddress();
            byte[] netBytes = netAddr.getAddress();
            if (ipBytes.length != netBytes.length) {
                return false;
            }
            if (prefix < 0 || prefix > ipBytes.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int bits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != netBytes[i]) {
                    return false;
                }
            }
            if (bits > 0 && fullBytes < ipBytes.length) {
                int mask = 0xFF << (8 - bits);
                if ((ipBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
