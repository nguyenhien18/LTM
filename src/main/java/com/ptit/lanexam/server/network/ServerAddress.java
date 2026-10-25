package com.ptit.lanexam.server.network;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ServerAddress {
    private ServerAddress() {}

    public static String connectionInfo(int port) {
        record Address(String ip, String adapter, boolean virtual, boolean local) {}
        List<Address> addresses = new ArrayList<>();
        try {
            var networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                var candidates = network.getInetAddresses();
                while (candidates.hasMoreElements()) {
                    var address = candidates.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
                        String adapter = network.getDisplayName() == null ? network.getName() : network.getDisplayName();
                        String name = adapter.toLowerCase(Locale.ROOT);
                        boolean virtual = network.isVirtual() || name.contains("virtual") || name.contains("vmware") || name.contains("vpn");
                        addresses.add(new Address(address.getHostAddress(), adapter, virtual, address.isSiteLocalAddress()));
                    }
                }
            }
        } catch (SocketException | SecurityException ignored) {
            // Address discovery must not prevent the exam server from starting.
        }
        String host = addresses.stream().filter(address -> !address.virtual())
                .sorted(Comparator.comparing(Address::local).reversed()
                        .thenComparing(Address::adapter).thenComparing(Address::ip))
                .map(Address::ip).findFirst().orElse(null);
        return host == null
                ? "Chưa tìm thấy IP LAN. Trên máy Server dùng IP: 127.0.0.1 | Cổng: " + port
                : "IP Server: " + host + " | Cổng: " + port;
    }
}
