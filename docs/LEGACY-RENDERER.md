# Legacy rider renderer audit

Status: static renderer audit **passed** for 1.7.10, 1.8.9 and 1.12.2. The final legacy artifacts and their native-test fixtures compile against the exact Forge APIs. Final native behavior remains **open** pending explicit Minecraft EULA acceptance; human visual confirmation is **unverified**.

## Finding

The vanilla living renderer uses a living vehicle as a rider body-yaw reference. In the exact Mojang clients, `RendererLivingEntity` (1.7.10 and 1.8.9) and `RenderLivingBase` (1.12.2) check `isRiding()` and whether the vehicle is an `EntityLivingBase`. When both checks pass, they interpolate the vehicle's `renderYawOffset`, wrap the rider head/body difference across the yaw boundary, clamp it to -85°..85°, then adjust the rider's rendered body yaw. A small correction is applied when the remaining angle is large. A living Silverfish or ArmorStand seat would enter that path; the final seat type avoids it.

The seat now uses vanilla `EntityFallingBlock` with the vanilla air block state. In each version it extends `Entity`, not `EntityLivingBase`, so the renderer's vehicle-derived yaw branch is skipped and the passenger keeps the ordinary player body/head yaw path. `SeatTransformer` guards the falling-block update so it cannot fall or place a block; `LegacyRuntime` continues to set its position and update the passenger on the server thread.

The vanilla falling-block renderer has no model to draw for air. In 1.7.10 and 1.8.9, `BlockAir#getRenderType()` returns `-1`, and `RenderFallingBlock` skips the matching world state or sends that render type through the no-model path. In 1.12.2, `BlockAir#getRenderType()` returns `EnumBlockRenderType.INVISIBLE`; `RenderFallingBlock` only continues for `MODEL`. The seat remains a built-in vanilla entity and does not add a registry entry.

This is an audit of renderer bytecode and seat class inheritance. It is not human visual confirmation and does not replace the pending native lifecycle test.

## Exact client and mapping evidence

Client jars came from the official [Mojang version manifest](https://launchermeta.mojang.com/mc/game/version_manifest_v2.json) and the version metadata listed below. SHA-1 is the client hash published in that metadata; SHA-256 was computed locally. The client classes themselves remain the original obfuscated Mojang bytecode. Forge-hosted MCP stable and SRG archives were used only to identify class/member names before inspecting them with `javap -p -c` (OpenJDK `javap` 17.0.20.1).

| Version | Version metadata | Client jar SHA-1 / SHA-256 | Mapped renderer / seat / block classes |
|---|---|---|---|
| 1.7.10 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/ed5d8789ed29872ea2ef1c348302b0c55e3f3468/1.7.10.json), SHA-256 `265c7e47aad4c74f6085a49667d3a828f48d63f484def2ae70b5e426e5b7cba6` | `e80d9b3bf5085002218d4be59e668bac718abbc6` / `a4fc2284657544e0f4bcc964f927c2fda3e3a205178ed1d5d58883aaf9780cce` ([client jar](https://launcher.mojang.com/v1/objects/e80d9b3bf5085002218d4be59e668bac718abbc6/client.jar)) | `RendererLivingEntity` / `EntityFallingBlock` / `BlockAir` (`boh` / `xj` / `aja`); seat superclass `Entity` (`sa`); renderer `RenderFallingBlock` (`bnq`) |
| 1.8.9 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json), SHA-256 `341af5f47a6b5bc137bfe681f4f3533e081041290aabbf9d7ebeae9881de768f` | `3870888a6c3d349d3771a3e9d16c9bf5e076b908` / `14f0d96d1a56fb4f5c3b2233d00699525893fe5ce3dcf181e7de59120595d298` ([client jar](https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar)) | `RendererLivingEntity` / `EntityFallingBlock` / `BlockAir` (`bjl` / `uy` / `aey`); seat superclass `Entity` (`pk`); renderer `RenderFallingBlock` (`bix`) |
| 1.12.2 | [Mojang metadata](https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json), SHA-256 `56dac2e1caa872d7e37a7b5523056cd812dca94f912ad2bd652817bee60edca4` | `0f275bc1547d01fa5f56ba34bdc87d981ee12daf` / `8ada07da5ee77dad3527bd7278fbd05ee1fc8a597813b216a871a2d7d64cc64f` ([client jar](https://piston-data.mojang.com/v1/objects/0f275bc1547d01fa5f56ba34bdc87d981ee12daf/client.jar)) | `RenderLivingBase` / `EntityFallingBlock` / `BlockAir` (`caa` / `ack` / `aom`); seat superclass `Entity` (`vg`); renderer `RenderFallingBlock` (`bzk`) |

Forge-hosted mappings used for those symbols:

| Version | MCP stable archive (SHA-256) | SRG archive (SHA-256) |
|---|---|---|
| 1.7.10 | [`mcp_stable-12-1.7.10.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/12-1.7.10/mcp_stable-12-1.7.10.zip) `83d303a80071582cd07d41b35aa77c178d609a27c69c8472b794636eb50905b0` | [`mcp-1.7.10-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip) `d49df5c445dcf00ada532ab3b0e4283bf4b05ac13f210647658dfd3cec8b34c2` |
| 1.8.9 | [`mcp_stable-22-1.8.9.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/22-1.8.9/mcp_stable-22-1.8.9.zip) `aeed0aaba9d159b7ce60a21e2dcc36adb249fade65ce2f76c730dd0ec7270763` | [`mcp-1.8.9-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip) `ea6fdd37e68569a378fd149b0ab7cfede1de7fa3e794f4ed81f9018366fbdc51` |
| 1.12.2 | [`mcp_stable-39-1.12.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip) `13a31f28c11f8f395ffe7e8563ade459f5a0ee46493abbbde3ce6e9493ac4152` | [`mcp-1.12.2-srg.zip`](https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.12.2/mcp-1.12.2-srg.zip) `e2ddd3a7bb65618ad0de1d8fc0334527e5c346180300d94bfc7d3727b1976b42` |

Forge describes [MCPConfig](https://github.com/MinecraftForge/MCPConfig) as its public SRG-mapping project. These are Forge-hosted MCP mappings, not Mojang-published named mappings.

## Build and test status

The final artifacts were rebuilt with OpenJDK 8u504b01 against the exact Forge APIs. The native-test runner's `--compile-only` mode compiled each fixture, including the air-placement check, without starting a server or writing EULA acceptance.

| Version | Forge API artifact SHA-256 | Tandemount artifact SHA-256 | Test fixture jar SHA-256 | Compile status |
|---|---|---|---|---|
| 1.7.10 | `e9ef6b96625c96628533aee0528dd5f08a8a1b0f48ddfa188e7ba0d51f13f492` | `52f5adc7b314e5ab8ddb18f3168238362fe517765a3f15e0b969c40244fbbbcd` | `993ff9a7da05bb7d5244b3aa88c9e16c5fddbdb9c522f8c463dc0c606c28b1e5` | passed |
| 1.8.9 | `6b392af4bdbb88fdcdfb69e1fb4c52ca46ef1ef686f624a373c6e0a470989a00` | `4a0d68a3eca436b5b8c20b2f929fe53a3f680c6f6b4487a6fe3b636c1f632bc8` | `4c641244ad574b1d4e5fc1b85eeef7402d7a5bf1c9c08bc3d73ce47e7fa9be38` | passed |
| 1.12.2 | `ff578d670d2c720a72f8fff31ea3d6868595c7e980ecdecba3254f307ef2c2a9` | `c4c8dfe551dcd4e7076c3f07060bff4801bb7874c58152e987e8f04dc06b45d4` | `2f0cb836f3ea5976d0a33110776e30c7e44580f334334671e9ec88a93b295a76` | passed |

Final native lifecycle and vanilla-protocol runs are **open** pending owner confirmation to accept the Minecraft EULA. The pending suite checks that repeated falling-block updates leave the world block as air, the seat stays alive and mounted, the seat rejects NBT saving, server stop removes it, and restart does not load it. One earlier isolated 1.7.10 experiment used the superseded `EntityItem` seat and failed because Forge rejects an air item stack; it is not evidence about the final falling-block strategy. Human visual confirmation remains **unverified**.
