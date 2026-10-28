package de.augmentia.oc.engine.runtime.tool.a2a;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * Egress guard for outbound A2A calls.
 *
 * <p>Mirrors the webhook egress checks: optional host allowlist, optional IP/CIDR
 * allowlist and optional TLS requirement. When all lists are empty and TLS is not
 * required, every URL is allowed (default-open, matching the webhook behaviour).</p>
 */
public final class A2AEgress {

    private A2AEgress() {
    }

    /**
     * @return an error message when the URL is blocked, or {@code null} when allowed.
     */
    public static String check(String url, List<String> allowedHosts, List<String> allowedIps, boolean tlsRequired) {
        if (url == null || url.isBlank()) return "url is blank";
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) return "invalid host";
            if (tlsRequired && !"https".equalsIgnoreCase(uri.getScheme())) {
                return "tls required but scheme is " + uri.getScheme();
            }
            if (allowedHosts != null && !allowedHosts.isEmpty()) {
                boolean hostAllowed = false;
                String h = host.toLowerCase();
                for (String allowed : allowedHosts) {
                    if (allowed == null || allowed.isBlank()) continue;
                    String a = allowed.trim().toLowerCase();
                    if (h.equals(a) || h.endsWith("." + a)) { hostAllowed = true; break; }
                }
                if (!hostAllowed) return "host not allowed: " + host;
            }
            if (allowedIps != null && !allowedIps.isEmpty()) {
                InetAddress[] addresses;
                try {
                    addresses = InetAddress.getAllByName(host);
                } catch (Exception e) {
                    return "cannot resolve host: " + host;
                }
                boolean ipAllowed = false;
                outer: for (InetAddress addr : addresses) {
                    String ip = addr.getHostAddress();
                    for (String allowed : allowedIps) {
                        if (allowed == null || allowed.isBlank()) continue;
                        String a = allowed.trim();
                        if (a.contains("/")) {
                            if (isInCidr(ip, a)) { ipAllowed = true; break outer; }
                        } else if (ip.equals(a)) {
                            ipAllowed = true; break outer;
                        }
                    }
                }
                if (!ipAllowed) return "ip not allowed for host: " + host;
            }
            return null;
        } catch (Exception e) {
            return "invalid url: " + e.getMessage();
        }
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
            if (ipBytes.length != netBytes.length) return false;
            int fullBytes = prefix / 8;
            int bits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != netBytes[i]) return false;
            }
            if (bits > 0 && fullBytes < ipBytes.length) {
                int mask = 0xFF << (8 - bits);
                if ((ipBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
