/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount.modern.fixture;

import dev.eyecrasher.tandemount.modern.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Opt-in loopback fixture. All riding and admin requests come over real sockets. */
public final class ModernWireTests {
    private static Object horse, driver, rider, observer, late;
    private static String request = "", error = "";
    private static int sequence;
    private static boolean initialized;
    private ModernWireTests() {}
    public static void tick(Object server, long ticks) {
        if (ticks < 20 || Boolean.getBoolean("tandemount.selftest.restart")) return;
        try {
            Path input = Paths.get("tandemount-wire-request.txt");
            if (Files.exists(input)) {
                String next = new String(Files.readAllBytes(input), StandardCharsets.UTF_8).trim();
                if (!next.equals(request)) { request = next; action(server, next); }
            }
            if (initialized) {
                if (late == null) {
                    late = find(server, "TmWireLate");
                    if (late != null) NativeTests.teleport(late, Names.call("server.overworld", server), 8.5, 80, 10.5);
                }
                writeState();
            }
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName();
            failure.printStackTrace();
            try { writeState(); } catch (Exception ignored) { }
        }
    }
    private static Object find(Object server, String name) throws Exception {
        for (Object player : (List<?>) Names.call("players.list", Names.call("server.players", server)))
            if (name.equals(Names.call("component.string", Names.call("entity.name", player)))) return player;
        return null;
    }
    @SuppressWarnings("unchecked") private static void action(Object server, String value) throws Exception {
        String[] parts = value.split(" ", 2);
        sequence = Integer.parseInt(parts[0]);
        String action = parts.length == 1 ? "" : parts[1];
        if (action.equals("setup")) {
            driver = find(server, "TmWireDriver"); rider = find(server, "TmWireRider"); observer = find(server, "TmWireObserver");
            if (driver == null || rider == null || observer == null) throw new IllegalStateException("Wire fixture clients are not connected");
            TandemRuntime.stop(server);
            Object level = Names.call("server.overworld", server);
            Names.call("level.chunk", level, 0, 0); // Isolated fixture preparation only.
            for (Object player : new Object[]{driver, rider, observer}) NativeTests.teleport(player, level, 8.5, 80, 10.5);
            horse = NativeTests.horse(level, 8.5);
            // Keep this isolated air-level fixture in interaction reach while
            // the real sockets complete tracking. Production horses are never
            // changed by these opt-in controls.
            Names.call("entity.gravity", horse, true);
            Names.call("mob.noAi", horse, true);
            initialized = true;
        } else if (action.equals("admin")) {
            Object dispatcher = Names.call("commands.dispatcher", Names.call("server.commands", server));
            ((com.mojang.brigadier.CommandDispatcher<Object>)dispatcher).execute("op TmWireObserver", Names.call("server.source", server));
        } else if (action.equals("move")) {
            Names.call("entity.position", horse, 10.5, 81, 8.5);
            Names.call("entity.rotation", horse, 90F);
            Names.call("entity.bodyRotation", horse, 90F);
            TandemRuntime.entityTick(horse);
        }
    }
    private static int id(Object entity) throws Exception { return entity == null ? -1 : ((Number)Names.call("entity.id", entity)).intValue(); }
    private static String location(Object entity) throws Exception {
        return entity == null ? "null" : "[" + Names.call("entity.x", entity) + "," + Names.call("entity.y", entity) + "," + Names.call("entity.z", entity) + "]";
    }
    private static int vehicle(Object entity) throws Exception { return entity == null ? -1 : id(Names.call("entity.vehicle", entity)); }
    private static int root(Object entity) throws Exception { return entity == null ? -1 : id(Names.call("entity.rootVehicle", entity)); }
    private static void writeState() throws Exception {
        StringBuilder out = new StringBuilder("{\"sequence\":").append(sequence).append(",\"error\":\"").append(error).append("\",\"horse\":").append(id(horse))
            .append(",\"driver\":").append(id(driver)).append(",\"rider\":").append(id(rider)).append(",\"observer\":").append(id(observer)).append(",\"late\":").append(id(late))
            .append(",\"driverVehicle\":").append(vehicle(driver)).append(",\"riderVehicle\":").append(vehicle(rider)).append(",\"observerVehicle\":").append(vehicle(observer))
            .append(",\"driverRoot\":").append(root(driver)).append(",\"riderRoot\":").append(root(rider)).append(",\"horsePosition\":").append(location(horse)).append(",\"riderPosition\":").append(location(rider))
            .append(",\"horseYaw\":").append(horse == null ? 0 : Names.call("entity.yaw", horse))
            .append(",\"horseBodyYaw\":").append(horse == null ? 0 : Names.field("LivingEntity.yBodyRot", horse))
            .append(",\"nativeAttachment\":").append(NativeAttachment.nativeAvailable())
            .append(",\"height\":").append(TandemRuntime.SETTINGS.height).append(",\"rear\":").append(TandemRuntime.SETTINGS.rear).append(",\"helpers\":[");
        boolean first = true;
        for (Object helper : TandemRuntime.helpersFor(horse)) {
            if (!first) out.append(','); first = false;
            boolean interaction = Names.TABLE.containsKey("c.Interaction") && Names.type("Interaction").isInstance(helper);
            boolean living = Names.type("LivingEntity").isInstance(helper);
            out.append("{\"id\":").append(id(helper)).append(",\"vehicle\":").append(vehicle(helper)).append(",\"position\":").append(location(helper))
                .append(",\"type\":\"").append(interaction ? "interaction" : Names.type("ArmorStand").isInstance(helper) ? "armor_stand" : living ? "llama" : Names.type("AreaEffectCloud").isInstance(helper) ? "area_effect_cloud" : "block_display").append("\",\"yaw\":").append(Names.call("entity.yaw", helper))
                .append(",\"pose\":\"").append(((Enum<?>)Names.call("entity.getPose", helper)).name()).append('"');
            if (interaction) out.append(",\"height\":").append(Names.call("interaction.getHeight", helper)).append(",\"width\":").append(Names.call("interaction.getWidth", helper));
            if (living) out.append(",\"scale\":").append(Names.has("living.scale") ? Names.call("living.scale", helper) : 1);
            if (Names.type("ArmorStand").isInstance(helper)) out.append(",\"marker\":").append(Names.call("stand.isMarker", helper));
            if (Names.type("AreaEffectCloud").isInstance(helper)) out.append(",\"radius\":").append(Names.call("cloud.getRadius", helper));
            out.append('}');
        }
        out.append("]}\n");
        Path temp = Paths.get("tandemount-wire-state.tmp");
        Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp, Paths.get("tandemount-wire-state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
