package dev.eyecrasher.tandemount.modern;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** Exact native protocol declarations for the opt-in local socket fixture. */
public final class ProtocolEvidence {
    private static boolean exported;
    private ProtocolEvidence() {}
    private static void addClassPacket(Properties packets, String prefix, Class<?> type, Object id) {
        String runtime = type.getName();
        String canonical = Names.TABLE.getProperty("p." + runtime, runtime);
        String simple = canonical.substring(canonical.lastIndexOf('.') + 1);
        String resource = simple.replaceFirst("^(Clientbound|Serverbound)", "").replaceFirst("Packet$", "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        packets.setProperty(prefix + "class." + simple, id.toString());
        packets.setProperty(prefix + "minecraft:" + resource, id.toString());
    }
    public static void tick(Object server, long ticks) {
        if (exported || ticks < 20) return;
        exported = true;
        try {
            final Properties packets = new Properties();
            packets.setProperty("minecraft", Names.TABLE.getProperty("minecraft"));
            packets.setProperty("platform", Names.TABLE.getProperty("platform"));
            int protocol = ((Number) (Names.has("protocol.version") ? Names.call("protocol.version", null) : Names.call("version.protocol", Names.call("protocol.current", null)))).intValue();
            packets.setProperty("protocol", Integer.toString(protocol));
            packets.setProperty("port", System.getProperty("tandemount.test.port", String.valueOf(Names.call("server.port", server))));
            for (String stage : new String[]{"login", "configuration", "game"}) {
                String owner = stage.equals("login") ? "LoginProtocols" : stage.equals("configuration") ? "ConfigurationProtocols" : "GameProtocols";
                if (Names.TABLE.containsKey("c." + owner)) {
                    for (String direction : new String[]{"CLIENTBOUND", "SERVERBOUND"}) {
                        final String prefix = stage + "." + direction.toLowerCase() + ".";
                        if (Names.hasField(owner + "." + direction + "_TEMPLATE")) {
                            Object template = Names.field(owner + "." + direction + "_TEMPLATE", null);
                            Object details = Names.has("protocol.details") ? Names.call("protocol.details", template) : template;
                            Method listing = Names.method(Names.has("protocol.packets") ? "protocol.packets" : "protocol.oldPackets");
                            Class<?> visitor = listing.getParameterTypes()[0];
                            Object callback = Proxy.newProxyInstance(visitor.getClassLoader(), new Class<?>[]{visitor}, (proxy, method, args) -> {
                                if (args != null && args.length == 2 && args[1] instanceof Number) {
                                    packets.setProperty(prefix + Names.call("packet.id", args[0]).toString(), args[1].toString());
                                }
                                return null;
                            });
                            listing.invoke(details, callback);
                        } else {
                            // 1.20.5/1.20.6 predate the visitor API. Read their real codec registry.
                            Object info = Names.field(owner + "." + direction, null);
                            if (Names.type("ProtocolUnbound").isInstance(info)) info = Names.call("protocol.bind", info, Function.identity());
                            Object codec = Names.call("protocol.codec", info);
                            Map<?, ?> ids = (Map<?, ?>) Names.field("IdDispatchCodec.toId", codec);
                            for (Map.Entry<?, ?> entry : ids.entrySet()) packets.setProperty(prefix + Names.call("packet.id", entry.getKey()).toString(), entry.getValue().toString());
                        }
                    }
                } else {
                    String field = stage.equals("game") ? "PLAY" : stage.toUpperCase();
                    if (!Names.hasField("ConnectionProtocol." + field)) continue;
                    Object state = Names.field("ConnectionProtocol." + field, null);
                    Map<?, ?> flows = (Map<?, ?>) Names.field("ConnectionProtocol.flows", state);
                    for (Map.Entry<?, ?> flow : flows.entrySet()) {
                        String direction = ((Enum<?>) flow.getKey()).name().toLowerCase();
                        String prefix = stage + "." + direction + ".";
                        if (Names.has("protocol.flowsPackets")) {
                            Map<?, ?> ids = (Map<?, ?>) Names.call("protocol.flowsPackets", state, flow.getKey());
                            for (Map.Entry<?, ?> entry : ids.entrySet()) {
                                addClassPacket(packets, prefix, (Class<?>) entry.getValue(), entry.getKey());
                            }
                        } else {
                            // 1.16.5 through 1.17.1 expose only the original packet sets.
                            Map<?, ?> ids = (Map<?, ?>) Names.field("ProtocolPacketSet.classToId", flow.getValue());
                            for (Map.Entry<?, ?> entry : ids.entrySet()) {
                                addClassPacket(packets, prefix, (Class<?>) entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
            }
            List<String> keys = new ArrayList<String>(packets.stringPropertyNames());
            Collections.sort(keys);
            StringBuilder text = new StringBuilder();
            for (String key : keys) text.append(key).append('=').append(packets.getProperty(key)).append('\n');
            Files.write(Paths.get("tandemount-protocol.properties"), text.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get("tandemount-network-ready.json"), ("{\"ready\":true,\"protocol\":" + protocol + ",\"port\":" + packets.getProperty("port") + "}\n").getBytes(StandardCharsets.UTF_8));
            TandemRuntime.log("NATIVE NETWORK READY " + protocol);
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.write(Paths.get("tandemount-network-error.txt"), failure.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) { failure.printStackTrace(); }
        }
    }
}
