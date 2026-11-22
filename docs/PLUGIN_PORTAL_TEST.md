# Paper 26.3 portal regression

The regression runs a disposable Paper server with two actual loopback protocol
connections. It mounts the first player on a saddled horse and joins the second
through Tandemount's interaction handler. The fixture then invokes the original
`NetherPortalBlock.getPortalDestination` and `Entity.teleport` methods, including
their ordinary Bukkit events. It records native exit coordinates, player
positions, helper ownership, rotation and portal cooldown.

Prepare the exact Paper 26.3 cache with `tools/cache_servers.py` and export
`protocol.properties` from the standard `TandemountNativeFixture` on that runtime.
Set `TANDEMOUNT_CACHE` and `TANDEMOUNT_JAVA_25` when they are outside the defaults.
The following example requires acceptance of the Minecraft EULA for this
disposable test:

```sh
python3 tools/plugin-portal-test.py \
  --plugin /path/to/tandemount-bukkit-1.0.0+26.3.jar \
  --protocol-export /path/to/TandemountNativeFixture/protocol.properties \
  --scenario return --accept-eula --output /path/to/portal-return.json
```

Add `--with-portallinked /path/to/PortalLinked.jar` to test both plugins together.
Run again with `--scenario cancel-portal` and `--scenario cancel-passenger` to
check cancellation of the horse's portal event and the second player's teleport.
`--scenario normal` checks only the outward transfer. Use distinct output files;
each run receives a fresh world. The server binds to `127.0.0.1`, port 25847 by
default; `--port` changes it.

The output binds assertions and raw observations to the candidate JAR, fixture,
native SDK, distribution, optional PortalLinked JAR and protocol-export hashes.
The affected personal-test-01 plugin fails the same native height and helper
cleanup assertions that the corrected candidate passes. A successful transfer
keeps the driver on the horse and ends the second seat at the correct portal
exit. The second player can mount again after arrival.

This invokes the native transfer directly to make the regression deterministic.
It does not test portal contact timing, a rendered client, arbitrary protection
plugins, every Paper build, or other Minecraft/platform versions. The fixture
and its test accounts must never be installed on an existing server.
