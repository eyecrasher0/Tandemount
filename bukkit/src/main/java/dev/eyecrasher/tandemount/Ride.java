/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;

final class Ride {
    final TandemountPlugin plugin;
    final Horse horse;
    final Player driver, rider;
    final Entity seat;
    final Object nativeSeat;
    final String worldName;
    Entity[] helpers;
    double attachmentRearward = Double.NaN;
    double attachmentLiftRatio = Double.NaN;
    volatile boolean closing, closed;
    volatile org.bukkit.event.vehicle.VehicleExitEvent driverExit, riderExit;
    double passengerOffset;
    Object legacyCollisionPlayer;
    Ride(TandemountPlugin plugin, Horse horse, Player driver, Player rider, Entity seat, Object nativeSeat) {
        this.plugin = plugin; this.horse = horse; this.driver = driver; this.rider = rider;
        this.seat = seat; this.nativeSeat = nativeSeat;
        this.worldName = horse.getWorld().getName();
        helpers = new Entity[] {seat};
    }
}
