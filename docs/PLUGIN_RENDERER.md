# Plugin seat and passenger rendering

The helper seat is a native vanilla falling-block entity. A passenger is
mounted on this seat while the driver remains mounted on the horse. The client
receives the native seat entity and its mount edge; no custom client entity is
created.

This seat type avoids the living-vehicle branch in the vanilla player renderer.
Official client bytecode for 1.20.6, 1.21.11, and 26.3 shows that
`LivingEntityRenderer` gets the rendered living entity's vehicle and only uses
the vehicle's body yaw when that vehicle is itself a `LivingEntity`. That branch
wraps and clamps the head-to-body yaw difference to ±85 degrees, then applies
the 0.2 adjustment when the absolute clamped difference exceeds 50 degrees.
In 1.20.6 the bytecode compares the squared difference to 2500, which is the
same 50-degree threshold; later audited clients compare the absolute value.
Otherwise the renderer uses the passenger's own body yaw. At each audited
version, the vanilla `FallingBlockEntity` class extends `Entity`, not
`LivingEntity`; the player renderer uses `LivingEntityRenderer` as its base.

`NativeAccess.createSeat` now rejects a helper unless its Bukkit entity is both
`EntityType.FALLING_BLOCK` and not a `LivingEntity`. The native fixture checks
the same invariant, and the loopback client capture ties that checked seat to
the entity ID in the received spawn and mount packets. This prevents a future
seat change from silently reintroducing the living-vehicle body-yaw path.

## Evidence limits

The official client bytecode establishes the renderer's type check and the
vanilla seat class hierarchy. The updated native fixture and loopback protocol
checks assert the runtime seat type and clientbound mount graph, but a native
runtime rerun against the final artifact is still pending. A protocol capture
does not execute the client renderer. No real client visual review has been
performed, so smoothness and appearance during a 179° to -179° turn remain open
for human confirmation.
