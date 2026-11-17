/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.Location;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Public entry points used by the small generated vanilla subclass. */
public final class NativeSeat {
    static final ConcurrentMap<Object, Ride> RIDES = new ConcurrentHashMap<Object, Ride>();
    private NativeSeat() { }

    public static double[] position(Object seat, double x, double y, double z) {
        Ride ride = RIDES.get(seat);
        if (ride == null || ride.closed) return new double[] {x, y, z};
        if (ride.plugin.nativeAccess.attachment != null) return new double[] {x, y, z};
        Location at = ride.horse.getLocation();
        Settings settings = ride.plugin.settings;
        double yaw = Math.toRadians(at.getYaw());
        return new double[] {at.getX() + Math.sin(yaw) * settings.rearward,
                at.getY() + settings.vertical - ride.passengerOffset,
                at.getZ() - Math.cos(yaw) * settings.rearward};
    }

    public static void tick(Object seat) {
        Ride ride = RIDES.get(seat);
        if (ride != null) ride.plugin.tickRide(ride);
    }
    public static boolean portalTeleport(Object seat, Object transition) {
        Ride ride = RIDES.get(seat);
        return ride != null && !ride.closed && ride.plugin.portalTeleport(ride, seat, transition);
    }
    public static void shape(Object seat) {
        Ride ride = RIDES.get(seat);
        if (ride == null || ride.closed) return;
        try { ride.plugin.nativeAccess.shape(seat); }
        catch (ReflectiveOperationException failure) {
            ride.closing = true; ride.plugin.debug("Native seat bounds validation failed", failure);
        }
    }
}
