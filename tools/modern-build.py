#!/usr/bin/env python3
"""Build exact-version modern mods using official loader SDK artifacts and mappings.

No mapped game classes or substitute API stubs are compiled into the mod. The
generated descriptor table is derived from the game's published mappings, and
Mixin's real loader API validates the bytecode hooks when the game boots.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import urllib.request
import zipfile
from cache_servers import java_for

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("TANDEMOUNT_CACHE", ROOT / ".agents" / "cache")) / "modern"
CACHE.mkdir(parents=True, exist_ok=True)


def fetch(url, target, sha1=None):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        req = urllib.request.Request(url, headers={"User-Agent": "Tandemount/1.0 (+https://github.com/EyeCrasher)"})
        try:
            with urllib.request.urlopen(req, timeout=120) as response:
                data = response.read()
            temp = target.with_suffix(target.suffix + ".part")
            temp.write_bytes(data)
            temp.replace(target)
        except urllib.error.HTTPError as exc:
            if exc.code != 403:
                raise
            subprocess.run(["curl", "-fsSL", "--retry", "2", url, "-o", str(target)], check=True)
    if sha1 and hashlib.sha1(target.read_bytes()).hexdigest() != sha1:
        raise ValueError("Upstream checksum mismatch: " + str(target))
    return target


def descriptor(type_name, classes):
    dims = 0
    while type_name.endswith("[]"):
        dims += 1
        type_name = type_name[:-2]
    base = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}.get(type_name)
    if base is None:
        base = "L" + classes.get(type_name, type_name).replace(".", "/") + ";"
    return "[" * dims + base


def mojang(text):
    classes, methods, fields = {}, {}, {}
    for line in text.splitlines():
        if line and not line[0].isspace() and not line.startswith("#"):
            named, obf = line[:-1].split(" -> ")
            classes[named] = obf
    owner = None
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        if not line[0].isspace():
            owner = line.split(" -> ")[0]
            continue
        member, obf = line.strip().split(" -> ")
        member = re.sub(r"^\d+:\d+:", "", member)
        if "(" in member:
            match = re.match(r"(\S+) (\S+)\((.*?)\)", member)
            if not match:
                continue
            result, name, params = match.groups()
            args = params.split(",") if params else []
            desc = "(" + "".join(descriptor(a, classes) for a in args) + ")" + descriptor(result, classes)
            methods[(owner, name, params)] = (classes[owner], obf, desc)
        else:
            typ, name = member.split(" ")
            fields[(owner, name)] = (classes[owner], obf, descriptor(typ, classes))
    return classes, methods, fields


def intermediary(path):
    with zipfile.ZipFile(path) as archive:
        lines = archive.read("mappings/mappings.tiny").decode().splitlines()
    classes, methods, fields = {}, {}, {}
    owner = None
    v2 = lines[0].startswith("tiny\t2")
    for line in lines[1:]:
        part = line.split("\t")
        if part[0] == "c":
            owner = part[1]
            classes[owner] = part[2]
        elif v2 and len(part) > 4 and part[0] == "" and part[1] in ("m", "f"):
            (methods if part[1] == "m" else fields)[(owner, part[3], part[2])] = part[4]
        elif not v2 and part[0] in ("METHOD", "FIELD"):
            (methods if part[0] == "METHOD" else fields)[(part[1], part[3], part[2])] = part[4]
    return classes, methods, fields


def remap_desc(desc, classes):
    return re.sub(r"L([^;]+);", lambda m: "L" + classes.get(m[1], m[1]) + ";", desc)


def classfile(data):
    """Read only JVM declarations and inheritance; no source reconstruction."""
    pos = 8
    def u2():
        nonlocal pos
        value = struct.unpack_from(">H", data, pos)[0]; pos += 2
        return value
    def u4():
        nonlocal pos
        value = struct.unpack_from(">I", data, pos)[0]; pos += 4
        return value
    count = u2(); pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]; pos += 1
        if tag == 1:
            size = u2(); pool[i] = data[pos:pos+size].decode("utf-8", errors="replace"); pos += size
        elif tag in (7, 8, 16, 19, 20): pool[i] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): pos += 4
        elif tag in (5, 6): pos += 8; i += 1
        elif tag == 15: pos += 3
        else: raise ValueError("Unknown classfile constant " + str(tag))
        i += 1
    u2(); name = pool[pool[u2()]]; parent = u2()
    parents = [pool[pool[parent]]] if parent else []
    parents.extend(pool[pool[u2()]] for _ in range(u2()))
    def members():
        nonlocal pos
        result = []
        for _ in range(u2()):
            access, name, desc = u2(), pool[u2()], pool[u2()]
            result.append((name, desc, access))
            for _ in range(u2()): u2(); size = u4(); pos += size
        return result
    fields = members(); methods = members()
    return name, parents, methods, fields


def descriptor_names(desc):
    result, index = [], 1
    while desc[index] != ")":
        dims = 0
        while desc[index] == "[": dims += 1; index += 1
        if desc[index] == "L":
            end = desc.index(";", index)
            name = desc[index+1:end].replace("/", "."); index = end + 1
        else:
            name = {"Z":"boolean","B":"byte","C":"char","S":"short","I":"int","J":"long","F":"float","D":"double"}[desc[index]]; index += 1
        result.append(name + "[]" * dims)
    return ",".join(result)


def identity_mappings(declared):
    names, methods, fields = {}, {}, {}
    for internal, (name, parents, declared_methods, declared_fields) in declared.items():
        owner = internal.replace("/", ".")
        names[owner] = owner
        for name, desc, access in declared_methods:
            methods[(owner, name, descriptor_names(desc))] = (owner, name, desc)
        for name, desc, access in declared_fields:
            fields[(owner, name)] = (owner, name, desc)
    return names, methods, fields


def vanilla(target):
    version = target["minecraft"]
    out = CACHE / (version + "-vanilla.jar")
    if not out.exists():
        prepared = CACHE / "servers" / ("fabric-" + version) / "server.jar"
        download = target["downloads"]["server"]
        bundled = prepared if prepared.exists() else fetch(download["url"], CACHE / (version + ".jar"), download["sha1"])
        with zipfile.ZipFile(bundled) as archive:
            nested = [name for name in archive.namelist() if name.startswith("META-INF/versions/") and name.endswith(".jar")]
            if nested: out.write_bytes(archive.read(nested[0]))
            else: out.write_bytes(bundled.read_bytes())
    return out


def declarations(target):
    result = {}
    with zipfile.ZipFile(vanilla(target)) as archive:
        for name in archive.namelist():
            if name.endswith(".class"):
                parsed = classfile(archive.read(name)); result[parsed[0]] = parsed
    return result


def coordinate(coordinate, repository, target_prefix="libraries"):
    parts = coordinate.split("@")[0].split(":")
    group, name, version = parts[:3]
    classifier = "-" + parts[3] if len(parts) > 3 else ""
    extension = coordinate.split("@", 1)[1] if "@" in coordinate else "jar"
    relative = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{classifier}.{extension}"
    return fetch(repository.rstrip("/") + "/" + relative, CACHE / target_prefix / relative)


def userdev(target, platform):
    entry = target["platforms"][platform]
    group, artifact = ("net.minecraftforge", "forge") if platform == "forge" else entry.get("artifact", "net.neoforged:neoforge").split(":")
    repository = "https://maven.minecraftforge.net" if platform == "forge" else "https://maven.neoforged.net/releases"
    archive = coordinate(f"{group}:{artifact}:{entry['sdk']}:userdev", repository)
    with zipfile.ZipFile(archive) as zipped:
        return json.loads(zipped.read("config.json"))


def tsrg(path):
    with zipfile.ZipFile(path) as archive:
        config = json.loads(archive.read("config.json"))
        lines = archive.read(config["data"]["mappings"]).decode().splitlines()
    classes, methods, fields = {}, {}, {}
    owner = None
    for line in lines:
        if line.startswith("tsrg2 "): continue
        if line and not line.startswith("\t"):
            part = line.split()
            owner = part[0]
            classes[owner] = part[1]
        elif line.startswith("\t") and not line.startswith("\t\t"):
            part = line.split()
            if len(part) >= 3 and part[1].startswith("("):
                methods[(owner, part[0], part[1])] = part[2]
            elif len(part) >= 2:
                fields[(owner, part[0], "")] = part[1]
    return classes, methods, fields


ALIASES = {
    "entity.passengers": ("Entity", "getPassengers", ""),
    "entity.vehicle": ("Entity", "getVehicle", ""),
    "entity.alive": ("Entity", "isAlive", ""),
    "entity.removed": ("Entity", "isRemoved", ""),
    "entity.level": ("Entity", "level", ""),
    "entity.x": ("Entity", "getX", ""),
    "entity.y": ("Entity", "getY", ""),
    "entity.z": ("Entity", "getZ", ""),
    "entity.yaw": ("Entity", "getYRot", ""),
    "entity.position": ("Entity", "setPos", "double,double,double"),
    "entity.rotation": ("Entity", "setYRot", "float"),
    "entity.invisible": ("Entity", "setInvisible", "boolean"),
    "entity.gravity": ("Entity", "setNoGravity", "boolean"),
    "entity.invulnerable": ("Entity", "setInvulnerable", "boolean"),
    "entity.mount": ("Entity", "startRiding", "net.minecraft.world.entity.Entity,boolean"),
    "entity.dismount": ("Entity", "stopRiding", ""),
    "entity.discard": ("Entity", "discard", ""),
    "entity.id": ("Entity", "getId", ""),
    "entity.uuid": ("Entity", "getUUID", ""),
    "entity.server": ("Entity", "getServer", ""),
    "entity.positionRider": ("Entity", "positionRider", "net.minecraft.world.entity.Entity"),
    "horse.tamed": ("AbstractHorse", "isTamed", ""),
    "horse.saddled": ("AbstractHorse", "isSaddled", ""),
    "horse.baby": ("AgeableMob", "isBaby", ""),
    "horse.tame": ("AbstractHorse", "setTamed", "boolean"),
    "horse.age": ("AgeableMob", "setAge", "int"),
    "horse.interact": ("Horse", "mobInteract", "net.minecraft.world.entity.player.Player,net.minecraft.world.InteractionHand"),
    "player.interact": ("Player", "interactOn", "net.minecraft.world.entity.Entity,net.minecraft.world.InteractionHand"),
    "horse.saddle": ("AbstractHorse", "equipSaddle", "net.minecraft.world.item.ItemStack,net.minecraft.sounds.SoundSource"),
    "cloud.radius": ("AreaEffectCloud", "setRadius", "float"),
    "cloud.radiusOnUse": ("AreaEffectCloud", "setRadiusOnUse", "float"),
    "cloud.radiusPerTick": ("AreaEffectCloud", "setRadiusPerTick", "float"),
    "cloud.durationOnUse": ("AreaEffectCloud", "setDurationOnUse", "int"),
    "cloud.duration": ("AreaEffectCloud", "setDuration", "int"),
    "cloud.waitTime": ("AreaEffectCloud", "setWaitTime", "int"),
    "cloud.waiting": ("AreaEffectCloud", "setWaiting", "boolean"),
    "cloud.getRadius": ("AreaEffectCloud", "getRadius", ""),
    "cloud.getDuration": ("AreaEffectCloud", "getDuration", ""),
    "cloud.isWaiting": ("AreaEffectCloud", "isWaiting", ""),
    "entity.noGravity": ("Entity", "isNoGravity", ""),
    "potion.hasEffects": ("PotionContents", "hasEffects", ""),
    "level.add": ("ServerLevel", "addFreshEntity", "net.minecraft.world.entity.Entity"),
    "level.loaded": ("Level", "hasChunkAt", "net.minecraft.core.BlockPos"),
    "entity.blockPosition": ("Entity", "blockPosition", ""),
    "server.commands": ("MinecraftServer", "getCommands", ""),
    "commands.dispatcher": ("Commands", "getDispatcher", ""),
    "source.permission": ("CommandSourceStack", "hasPermission", "int"),
    "source.message": ("CommandSourceStack", "sendSystemMessage", "net.minecraft.network.chat.Component"),
    "server.overworld": ("MinecraftServer", "overworld", ""),
    "server.stop": ("MinecraftServer", "halt", "boolean"),
    "server.source": ("MinecraftServer", "createCommandSourceStack", ""),
    "entity.source": ("Entity", "createCommandSourceStack", ""),
    "entity.save": ("Entity", "save", "net.minecraft.nbt.CompoundTag"),
    "entity.savePassenger": ("Entity", "saveAsPassenger", "net.minecraft.nbt.CompoundTag"),
    "entity.shouldSave": ("Entity", "shouldBeSaved", ""),
    "component.literal": ("Component", "literal", "java.lang.String"),
    "component.style": ("Component", "getStyle", ""),
    "component.setStyle": ("MutableComponent", "setStyle", "net.minecraft.network.chat.Style"),
    "style.click": ("Style", "withClickEvent", "net.minecraft.network.chat.ClickEvent"),
    "client.default": ("ClientInformation", "createDefault", ""),
    "cookie.initial": ("CommonListenerCookie", "createInitial", "com.mojang.authlib.GameProfile,boolean"),
    "level.chunk": ("Level", "getChunk", "int,int"),
    "living.equip": ("LivingEntity", "setItemSlot", "net.minecraft.world.entity.EquipmentSlot,net.minecraft.world.item.ItemStack"),
    "source.permissions": ("CommandSourceStack", "permissions", ""),
    "permissions.has": ("PermissionSet", "hasPermission", "net.minecraft.server.permissions.Permission"),
    "update.periodic": ("UpdateInterval", "periodic", "int"),
    "player.disconnect": ("ServerPlayer", "hasDisconnected", ""),
    "player.message": ("ServerPlayer", "sendSystemMessage", "net.minecraft.network.chat.Component"),
    "server.players": ("MinecraftServer", "getPlayerList", ""),
    "server.levels": ("MinecraftServer", "getAllLevels", ""),
    "level.dimension": ("Level", "dimension", ""),
    "dimension.location": ("ResourceKey", "location", ""),
    "level.addPlayer": ("ServerLevel", "addNewPlayer", "net.minecraft.server.level.ServerPlayer"),
    "entity.setLevel": ("Entity", "setLevel", "net.minecraft.world.level.Level"),
    "player.setLevel": ("ServerPlayer", "setLevel", "net.minecraft.server.level.ServerLevel"),
    "player.teleport": ("ServerPlayer", "teleportTo", "net.minecraft.server.level.ServerLevel,double,double,double,java.util.Set,float,float,boolean"),
    "living.health": ("LivingEntity", "getHealth", ""),
    "living.setHealth": ("LivingEntity", "setHealth", "float"),
    "player.nativeDisconnect": ("ServerPlayer", "disconnect", ""),
    "level.entities": ("ServerLevel", "getAllEntities", ""),
    "tracker.pairing": ("ServerEntity", "sendPairingData", "net.minecraft.server.level.ServerPlayer,java.util.function.Consumer"),
    "players.list": ("PlayerList", "getPlayers", ""),
    "server.port": ("MinecraftServer", "getPort", ""),
    "protocol.version": ("SharedConstants", "getProtocolVersion", ""),
    "protocol.current": ("SharedConstants", "getCurrentVersion", ""),
    "version.protocol": ("WorldVersion", "getProtocolVersion", ""),
    "protocol.details": ("ProtocolDetailsProvider", "details", ""),
    "protocol.packets": ("ProtocolDetails", "listPackets", "net.minecraft.network.ProtocolInfo$Details$PacketVisitor"),
    "protocol.oldPackets": ("ProtocolUnbound", "listPackets", "net.minecraft.network.ProtocolInfo$Unbound$PacketVisitor"),
    "packet.id": ("PacketType", "id", ""),
    "protocol.codec": ("ProtocolInfo", "codec", ""),
    "protocol.bind": ("ProtocolUnbound", "bind", "java.util.function.Function"),
    "codec.entryType": ("IdDispatchEntry", "type", ""),
    "protocol.flowsPackets": ("ConnectionProtocol", "getPacketsByIds", "net.minecraft.network.protocol.PacketFlow"),
    "protocol.setPackets": ("ProtocolPacketSet", "getAllPackets", ""),
    "protocol.flowCodec": ("ConnectionProtocol", "codec", "net.minecraft.network.protocol.PacketFlow"),
    "connection.protocolKey": ("Connection", "getProtocolKey", "net.minecraft.network.protocol.PacketFlow"),
}

CLASSES = {
    "Entity": "net.minecraft.world.entity.Entity",
    "Horse": "net.minecraft.world.entity.animal.horse.Horse",
    "AbstractHorse": "net.minecraft.world.entity.animal.horse.AbstractHorse",
    "AreaEffectCloud": "net.minecraft.world.entity.AreaEffectCloud",
    "PotionContents": "net.minecraft.world.item.alchemy.PotionContents",
    "Player": "net.minecraft.world.entity.player.Player",
    "ServerPlayer": "net.minecraft.server.level.ServerPlayer",
    "Level": "net.minecraft.world.level.Level",
    "LevelReader": "net.minecraft.world.level.LevelReader",
    "ServerLevel": "net.minecraft.server.level.ServerLevel",
    "AgeableMob": "net.minecraft.world.entity.AgeableMob",
    "MinecraftServer": "net.minecraft.server.MinecraftServer",
    "Commands": "net.minecraft.commands.Commands",
    "CommandSourceStack": "net.minecraft.commands.CommandSourceStack",
    "Component": "net.minecraft.network.chat.Component",
    "InteractionResult": "net.minecraft.world.InteractionResult",
    "InteractionHand": "net.minecraft.world.InteractionHand",
    "CompoundTag": "net.minecraft.nbt.CompoundTag",
    "BlockPos": "net.minecraft.core.BlockPos",
    "ChunkPos": "net.minecraft.world.level.ChunkPos",
    "Vec3": "net.minecraft.world.phys.Vec3",
    "ResourceKey": "net.minecraft.resources.ResourceKey",
    "AddEntityPacket": "net.minecraft.network.protocol.game.ClientboundAddEntityPacket",
    "EntityDataPacket": "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket",
    "PassengersPacket": "net.minecraft.network.protocol.game.ClientboundSetPassengersPacket",
    "ServerEntity": "net.minecraft.server.level.ServerEntity",
    "EntityType": "net.minecraft.world.entity.EntityType",
    "EntityTypes": "net.minecraft.world.entity.EntityTypes",
    "ClientInformation": "net.minecraft.server.level.ClientInformation",
    "ProfilePublicKey": "net.minecraft.world.entity.player.ProfilePublicKey",
    "Connection": "net.minecraft.network.Connection",
    "PacketFlow": "net.minecraft.network.protocol.PacketFlow",
    "ServerGamePacketListenerImpl": "net.minecraft.server.network.ServerGamePacketListenerImpl",
    "CommonListenerCookie": "net.minecraft.server.network.CommonListenerCookie",
    "PlayerList": "net.minecraft.server.players.PlayerList",
    "TextComponent": "net.minecraft.network.chat.TextComponent",
    "MutableComponent": "net.minecraft.network.chat.MutableComponent",
    "Style": "net.minecraft.network.chat.Style",
    "ClickEvent": "net.minecraft.network.chat.ClickEvent",
    "ClickAction": "net.minecraft.network.chat.ClickEvent$Action",
    "RunCommand": "net.minecraft.network.chat.ClickEvent$RunCommand",
    "SoundSource": "net.minecraft.sounds.SoundSource",
    "Items": "net.minecraft.world.item.Items",
    "ItemStack": "net.minecraft.world.item.ItemStack",
    "ItemLike": "net.minecraft.world.level.ItemLike",
    "ServerPlayerGameMode": "net.minecraft.server.level.ServerPlayerGameMode",
    "Mob": "net.minecraft.world.entity.Mob",
    "LivingEntity": "net.minecraft.world.entity.LivingEntity",
    "EquipmentSlot": "net.minecraft.world.entity.EquipmentSlot",
    "UpdateInterval": "net.minecraft.world.entity.UpdateInterval",
    "PermissionSet": "net.minecraft.server.permissions.PermissionSet",
    "Permission": "net.minecraft.server.permissions.Permission",
    "Permissions": "net.minecraft.server.permissions.Permissions",
    "SharedConstants": "net.minecraft.SharedConstants",
    "WorldVersion": "net.minecraft.WorldVersion",
    "ConnectionProtocol": "net.minecraft.network.ConnectionProtocol",
    "ProtocolPacketSet": "net.minecraft.network.ConnectionProtocol$PacketSet",
    "ProtocolInfo": "net.minecraft.network.ProtocolInfo",
    "ProtocolDetailsProvider": "net.minecraft.network.ProtocolInfo$DetailsProvider",
    "ProtocolDetails": "net.minecraft.network.ProtocolInfo$Details",
    "ProtocolUnbound": "net.minecraft.network.ProtocolInfo$Unbound",
    "PacketType": "net.minecraft.network.protocol.PacketType",
    "LoginProtocols": "net.minecraft.network.protocol.login.LoginProtocols",
    "ConfigurationProtocols": "net.minecraft.network.protocol.configuration.ConfigurationProtocols",
    "GameProtocols": "net.minecraft.network.protocol.game.GameProtocols",
    "IdDispatchCodec": "net.minecraft.network.codec.IdDispatchCodec",
    "IdDispatchEntry": "net.minecraft.network.codec.IdDispatchCodec$Entry",
}


def forge_config_shutdown_fix(target, platform):
    # This exact SDK puts its COMMON config cleanup in showGui(), leaving the
    # dedicated server's non-daemon NightConfig worker alive after world saves.
    return (platform == "forge" and target["minecraft"] == "1.21.9"
            and target["platforms"][platform]["sdk"] == "1.21.9-59.0.5")


def mapped_table(target, platform):
    version = target["minecraft"]
    data = target["downloads"]
    declared = declarations(target)
    if "server_mappings" in data:
        mapping = fetch(data["server_mappings"]["url"], CACHE / (version + ".mapping.txt"), data["server_mappings"]["sha1"])
        names, methods, fields = mojang(mapping.read_text())
    else:
        names, methods, fields = identity_mappings(declared)
        # Mojang's un-obfuscated 26.x game relocated the equine package.
    CLASSES["AgeableMob"] = "net.minecraft.world.entity.AgableMob" if "net.minecraft.world.entity.AgableMob" in names else "net.minecraft.world.entity.AgeableMob"
    for short in ("Horse", "AbstractHorse"):
        relocated = "net.minecraft.world.entity.animal.equine." + short
        if relocated in names: CLASSES[short] = relocated
        else: CLASSES[short] = "net.minecraft.world.entity.animal.horse." + short
    runtime_classes, runtime_methods, runtime_fields = {}, {}, {}
    if platform == "fabric" and "server_mappings" in data:
        inter = fetch("https://maven.fabricmc.net/net/fabricmc/intermediary/" + version + "/intermediary-" + version + "-v2.jar", CACHE / ("intermediary-" + version + ".jar"))
        runtime_classes, runtime_methods, runtime_fields = intermediary(inter)
    elif platform != "fabric":
        config = userdev(target, platform)
        runtime_classes = {obf.replace(".", "/"): named.replace(".", "/") for named, obf in names.items()}
        if platform == "forge" or (platform == "neoforge" and version == "1.20.1"):
            repository = "https://maven.minecraftforge.net" if platform == "forge" else "https://maven.neoforged.net/releases"
            srg_classes, runtime_methods, runtime_fields = tsrg(coordinate(config["mcp"], repository))
            # Forge 1.16.5 still runs MCP package names. Newer production
            # servers keep official classes despite userdev's synthetic C_ names.
            if version == "1.16.5": runtime_classes = srg_classes
        else:
            # NeoForge's production namespace is official names from 1.20.2.
            for (owner, name, params), (obf_owner, obf_name, desc) in methods.items():
                runtime_methods[(obf_owner.replace(".", "/"), obf_name, desc)] = name
            for (owner, name), (obf_owner, obf_name, desc) in fields.items():
                runtime_fields[(obf_owner.replace(".", "/"), obf_name, desc)] = name
    table = {"minecraft": version, "platform": platform}
    for short, name in CLASSES.items():
        if name in names:
            table["c." + short] = runtime_classes.get(names[name].replace(".", "/"), names[name].replace(".", "/")).replace("/", ".")

    def mapped_member(member, is_field=False):
        owner, name, desc = member
        old_owner = owner.replace(".", "/")
        member_map = runtime_fields if is_field else runtime_methods
        queue = [old_owner]; seen = set(); new_name = None
        while queue and new_name is None:
            candidate = queue.pop(0)
            if candidate in seen: continue
            seen.add(candidate)
            new_name = member_map.get((candidate, name, desc), member_map.get((candidate, name, "")))
            if candidate in declared: queue.extend(declared[candidate][1])
        if new_name is None: new_name = name
        if platform == "forge" or (platform == "neoforge" and version == "1.20.1"):
            entries = fields if is_field else methods
            official_names = [key[1] for key, value in entries.items() if value == member]
            if official_names and official_names[0] != new_name:
                new_name += "|" + official_names[0]
        return runtime_classes.get(old_owner, old_owner).replace("/", ".") + "#" + new_name + "#" + remap_desc(desc, runtime_classes)

    for key, (owner, name, params) in ALIASES.items():
        member = methods.get((CLASSES[owner], name, params))
        if member:
            table["m." + key] = mapped_member(member)
    # Vanilla names changed while behavior stayed the same.
    fallbacks = {
        "entity.level": [("Entity", "getLevel", "")],
        "level.loaded": [("LevelReader", "hasChunkAt", "net.minecraft.core.BlockPos")],
        "level.chunk": [("LevelReader", "getChunk", "int,int")],
        "entity.mount": [("Entity", "startRiding", "net.minecraft.world.entity.Entity")],
        "entity.invulnerable": [("Entity", "setPermanentlyInvulnerable", "boolean")],
        "entity.source": [("ServerPlayer", "createCommandSourceStack", "")],
        "player.interact": [("Player", "interactOn", "net.minecraft.world.entity.Entity,net.minecraft.world.InteractionHand,net.minecraft.world.phys.Vec3")],
        "entity.yaw": [("Entity", "getYHeadRot", "")],
        "entity.discard": [("Entity", "remove", "")],
        "source.message": [("CommandSourceStack", "sendSuccess", "net.minecraft.network.chat.Component,boolean")],
        "player.message": [("ServerPlayer", "sendMessage", "net.minecraft.network.chat.Component,java.util.UUID")],
        "horse.saddle": [("AbstractHorse", "equipSaddle", "net.minecraft.sounds.SoundSource")],
        "horse.saddled": [("Mob", "isSaddled", "")],
        "cookie.initial": [("CommonListenerCookie", "createInitial", "com.mojang.authlib.GameProfile")],
        "tracker.pairing": [("ServerEntity", "sendPairingData", "java.util.function.Consumer")],
        "player.teleport": [("ServerPlayer", "teleportTo", "net.minecraft.server.level.ServerLevel,double,double,double,java.util.Set,float,float"), ("ServerPlayer", "teleportTo", "net.minecraft.server.level.ServerLevel,double,double,double,float,float")],
        "dimension.location": [("ResourceKey", "identifier", "")],
    }
    for key, choices in fallbacks.items():
        if "m." + key in table:
            continue
        for owner, name, params in choices:
            member = methods.get((CLASSES[owner], name, params))
            if member:
                table["m." + key] = mapped_member(member)
                break
    if platform == "neoforge" and version != "1.20.1":
        # NeoForge 1.20.4 through 1.21.6 replaces vanilla's pairing collector.
        # Identify that exact API from this SDK, not from a version range or
        # the mere absence of a vanilla method at runtime.
        repository = "https://maven.neoforged.net/releases"
        acceptor = "net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor"
        universal = coordinate(config["universal"], repository)
        with zipfile.ZipFile(universal) as archive:
            member = acceptor.replace(".", "/") + ".class"
            acceptor_declaration = classfile(archive.read(member)) if member in archive.namelist() else None
        if acceptor_declaration is not None:
            constructor = "(Ljava/util/function/Consumer;)V"
            if not any(name == "<init>" and desc == constructor and access & 1
                       for name, desc, access in acceptor_declaration[2]):
                raise ValueError("Unsupported exact NeoForge pairing collector constructor: " + version)
            group, artifact = target["platforms"][platform].get("artifact", "net.neoforged:neoforge").split(":")
            sdk = coordinate(f"{group}:{artifact}:{target['platforms'][platform]['sdk']}:userdev", repository)
            with zipfile.ZipFile(sdk) as archive:
                patch = archive.read("patches/net/minecraft/server/level/ServerEntity.java.patch").decode()
            signature = (r"^\+\s+public void sendPairingData\(ServerPlayer \w+, " + re.escape(acceptor)
                         + r"<net\.minecraft\.network\.protocol\.game\.ClientGamePacketListener> \w+\) \{$")
            if not re.search(signature, patch, re.MULTILINE):
                raise ValueError("Unsupported exact NeoForge pairing declaration: " + version)
            pairing = table["m.tracker.pairing"].split("#")
            expected = "(L" + table["c.ServerPlayer"].replace(".", "/") + ";Ljava/util/function/Consumer;)V"
            if pairing[2] != expected:
                raise ValueError("Unexpected vanilla pairing descriptor for NeoForge: " + version)
            table["c.PairingAcceptor"] = acceptor
            pairing[2] = expected.replace("Ljava/util/function/Consumer;", "L" + acceptor.replace(".", "/") + ";")
            table["m.tracker.pairing"] = "#".join(pairing)
    for owner, fields_wanted in {"Entity": ["noPhysics", "level", "yRot", "removed", "position", "blockPosition", "chunkPosition"], "Level": ["isClientSide"], "LivingEntity": ["yBodyRot"], "InteractionResult": ["PASS", "SUCCESS"], "EntityType": ["HORSE"], "InteractionHand": ["MAIN_HAND"], "PacketFlow": ["SERVERBOUND"], "ServerPlayer": ["connection"], "ServerEntity": ["updateInterval", "entity"], "Connection": ["channel"], "ClickAction": ["RUN_COMMAND"], "SoundSource": ["PLAYERS"], "Items": ["SADDLE"], "EquipmentSlot": ["SADDLE"], "Permissions": ["COMMANDS_GAMEMASTER"], "ConnectionProtocol": ["LOGIN", "CONFIGURATION", "PLAY", "flows"], "PacketFlow": ["CLIENTBOUND", "SERVERBOUND"], "ProtocolPacketSet": ["classToId"], "IdDispatchCodec": ["toId", "byId"], "LoginProtocols": ["CLIENTBOUND_TEMPLATE", "SERVERBOUND_TEMPLATE", "CLIENTBOUND", "SERVERBOUND"], "ConfigurationProtocols": ["CLIENTBOUND_TEMPLATE", "SERVERBOUND_TEMPLATE", "CLIENTBOUND", "SERVERBOUND"], "GameProtocols": ["CLIENTBOUND_TEMPLATE", "SERVERBOUND_TEMPLATE", "CLIENTBOUND", "SERVERBOUND"]}.items():
        canonical = CLASSES.get(owner, "net.minecraft.world.entity." + owner)
        for name in fields_wanted:
            member = fields.get((canonical, name))
            if member is None and owner == "EntityType": member = fields.get((CLASSES["EntityTypes"], name))
            if member:
                table["f." + owner + "." + name] = mapped_member(member, True)
    for name in ("INFINITE_DURATION", "effects", "potionContents"):
        member = fields.get((CLASSES["AreaEffectCloud"], name))
        if member:
            table["f.AreaEffectCloud." + name] = mapped_member(member, True)
    # Dedicated 1.16.5 strips the passenger packet's client getters even though
    # its published server mappings list them. Its exact fields remain present.
    for owner, wanted in {"AddEntityPacket": ("id", "x", "y", "z"), "EntityDataPacket": ("id", "packedItems"), "PassengersPacket": ("vehicle", "passengers")}.items():
        for name in wanted:
            member = fields.get((CLASSES[owner], name))
            if member:
                table["f." + owner + "." + name] = mapped_member(member, True)
    for canonical in names:
        if canonical.startswith("net.minecraft.network.protocol.") and canonical.endswith("Packet"):
            runtime = runtime_classes.get(names[canonical].replace(".", "/"), names[canonical].replace(".", "/")).replace("/", ".")
            table["p." + runtime] = canonical
    # Every hook is generated from an exact official signature, not a fuzzy match.
    hooks = {
        "horse": ("Horse", "mobInteract", "net.minecraft.world.entity.player.Player,net.minecraft.world.InteractionHand"),
        "horseRide": ("AbstractHorse", "doPlayerRide", "net.minecraft.world.entity.player.Player"),
        "tick": ("MinecraftServer", "tickServer", "java.util.function.BooleanSupplier"),
        "stop": ("MinecraftServer", "stopServer", ""),
        "entityTick": ("ServerLevel", "tickNonPassenger", "net.minecraft.world.entity.Entity"),
        "save": ("Entity", "save", "net.minecraft.nbt.CompoundTag"),
        "savePassenger": ("Entity", "saveAsPassenger", "net.minecraft.nbt.CompoundTag"),
        "shouldSave": ("Entity", "shouldBeSaved", ""),
        "hurt": ("AreaEffectCloud", "hurt", "net.minecraft.world.damagesource.DamageSource,float"),
    }
    for key, (owner, name, params) in hooks.items():
        member = methods.get((CLASSES[owner], name, params))
        if member is None and key in ("save", "savePassenger", "hurt"):
            renamed = "hurtServer" if key == "hurt" else name
            for (declaring, candidate, parameters), value in methods.items():
                if declaring == CLASSES[owner] and candidate == renamed:
                    member = value
                    if key in ("save", "savePassenger"): table["m.entity." + ("save" if key == "save" else "savePassenger")] = mapped_member(member)
                    break
        if member:
            table["h." + key] = mapped_member(member)
            if key == "hurt": table["m.seat.hurt"] = mapped_member(member)
        elif key not in ("hurt", "shouldSave"):
            raise ValueError("Exact vanilla hook unavailable: " + key + " " + version)
    # Select only a complete, known constructor signature that is actually
    # declared in this exact official server. Parameter count is insufficient:
    # 1.19's nullable profile key shares arity with game mode and client info.
    player_base = "net.minecraft.server.MinecraftServer,net.minecraft.server.level.ServerLevel,com.mojang.authlib.GameProfile"
    player_signatures = [player_base] + [player_base + "," + extra for extra in (
        CLASSES["ServerPlayerGameMode"], CLASSES["ProfilePublicKey"], CLASSES["ClientInformation"])]
    player_constructors = []
    for params in player_signatures:
        member = methods.get((CLASSES["ServerPlayer"], "<init>", params))
        if member and any(name == "<init>" and desc == member[2] and access & 1
                          for name, desc, access in declared[member[0].replace(".", "/")][2]):
            player_constructors.append(member)
    if len(player_constructors) != 1:
        raise ValueError("No unique supported exact fixture player constructor: " + version)
    table["constructor.player"] = mapped_member(player_constructors[0])
    listener_base = "net.minecraft.server.MinecraftServer,net.minecraft.network.Connection,net.minecraft.server.level.ServerPlayer"
    listener_constructors = []
    for params in (listener_base, listener_base + "," + CLASSES["CommonListenerCookie"]):
        member = methods.get((CLASSES["ServerGamePacketListenerImpl"], "<init>", params))
        if member and any(name == "<init>" and desc == member[2] and access & 1
                          for name, desc, access in declared[member[0].replace(".", "/")][2]):
            listener_constructors.append((params, member))
    if len(listener_constructors) != 1:
        raise ValueError("No unique supported exact fixture listener constructor: " + version)
    listener_params, listener = listener_constructors[0]
    table["constructor.listener"] = mapped_member(listener)
    if listener_params != listener_base:
        factories = []
        for params in ("com.mojang.authlib.GameProfile", "com.mojang.authlib.GameProfile,boolean"):
            member = methods.get((CLASSES["CommonListenerCookie"], "createInitial", params))
            if member and table.get("m.cookie.initial") == mapped_member(member) and any(
                    name == member[1] and desc == member[2] and access & 9 == 9
                    for name, desc, access in declared[member[0].replace(".", "/")][2]):
                factories.append(member)
        if len(factories) != 1:
            raise ValueError("No supported exact fixture listener cookie factory: " + version)
    for (owner, name, params), member in methods.items():
        if owner == CLASSES["ServerEntity"] and name == "<init>" and "net.minecraft.world.entity.Entity" in params.split(","):
            table["h.tracker"] = mapped_member(member)
            break
    if forge_config_shutdown_fix(target, platform):
        member = methods.get(("net.minecraft.server.dedicated.DedicatedServer", "onServerExit", ""))
        if member is None or member[2] != "()V" or not any(
                name == member[1] and desc == member[2] and access & 1
                for name, desc, access in declared[member[0].replace(".", "/")][2]):
            raise ValueError("Exact Forge config shutdown exit method is unavailable")
        table["compat.forgeConfigShutdown"] = mapped_member(member)
    return table


def fabric_sdk(target):
    version, loader = target["minecraft"], target["platforms"]["fabric"]["loader"]
    profile_path = fetch(f"https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/server/json", CACHE / f"fabric-{version}-profile.json")
    profile = json.loads(profile_path.read_text())
    dependencies = []
    for library in profile["libraries"]:
        group, name, ver = library["name"].split(":")[:3]
        relative = "/".join((group.replace(".", "/"), name, ver, name + "-" + ver + ".jar"))
        url = library.get("url", "https://libraries.minecraft.net/") + relative
        dependencies.append(fetch(url, CACHE / "libraries" / relative))
    metadata = json.loads(fetch(target["minecraft_metadata"], CACHE / (version + ".metadata.json")).read_text())
    for library in metadata["libraries"]:
        if library["name"].startswith("com.mojang:brigadier:"):
            artifact = library["downloads"]["artifact"]
            dependencies.append(fetch(artifact["url"], CACHE / "libraries" / artifact["path"], artifact["sha1"]))
    return dependencies


def forge_sdk(target, platform):
    config = userdev(target, platform)
    repository = "https://maven.minecraftforge.net" if platform == "forge" else "https://maven.neoforged.net/releases"
    dependencies = []
    if "universal" in config:
        dependencies.append(coordinate(config["universal"], repository))
    for library in config["libraries"]:
        if (library.startswith("org.ow2.asm:") or ":mixin:" in library or ":sponge-mixin:" in library or ":javafmllanguage:" in library or (library.startswith(("net.neoforged.fancymodloader:loader:", "net.neoforged.fancymodloader:language-java:")))
                or (forge_config_shutdown_fix(target, platform)
                    and library == "net.minecraftforge:fmlcore:" + target["platforms"][platform]["sdk"])):
            dependencies.append(coordinate(library, repository))
    metadata = json.loads(fetch(target["minecraft_metadata"], CACHE / (target["minecraft"] + ".metadata.json")).read_text())
    for library in metadata["libraries"]:
        if library["name"].startswith("com.mojang:brigadier:"):
            artifact = library["downloads"]["artifact"]
            dependencies.append(fetch(artifact["url"], CACHE / "libraries" / artifact["path"], artifact["sha1"]))
    return dependencies


def build(target, platform):
    version = target["minecraft"]
    if not target["platforms"][platform]["available"]:
        raise ValueError(f"No upstream: {platform} {version}")
    table = mapped_table(target, platform)
    sdk = fabric_sdk(target) if platform == "fabric" else forge_sdk(target, platform)
    work = ROOT / ".agents" / "modern-build" / (platform + "-" + version)
    classes = work / "classes"
    # Only disposable outputs owned by this exact build are cleared.
    for disposable in (classes, work / "generated"):
        if disposable.exists(): shutil.rmtree(disposable)
    classes.mkdir(parents=True, exist_ok=True)
    generated = work / "generated" / "dev" / "eyecrasher" / "tandemount" / "modern" / "mixin"
    generated.mkdir(parents=True, exist_ok=True)
    mixins = []
    hook_owners = sorted(set(v.split("#")[0] for k, v in table.items() if k.startswith("h.")))
    for index, owner in enumerate(hook_owners):
        name = "Target" + str(index)
        (generated / (name + ".java")).write_text('package dev.eyecrasher.tandemount.modern.mixin;\n@org.spongepowered.asm.mixin.Mixin(targets="' + owner + '", remap=false) public abstract class ' + name + ' {}\n')
        mixins.append(name)
    if forge_config_shutdown_fix(target, platform):
        owner, aliases, descriptor = table["compat.forgeConfigShutdown"].split("#")
        selectors = ", ".join(json.dumps(alias + descriptor) for alias in aliases.split("|"))
        (generated / "ForgeConfigShutdown.java").write_text('''package dev.eyecrasher.tandemount.modern.mixin;
@org.spongepowered.asm.mixin.Mixin(targets="''' + owner + '''", remap=false)
public abstract class ForgeConfigShutdown {
    @org.spongepowered.asm.mixin.injection.Inject(method={''' + selectors + '''},
        at=@org.spongepowered.asm.mixin.injection.At("RETURN"), require=1, allow=1, remap=false)
    private void tandemount$closeForgeConfigWatchers(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        net.minecraftforge.fml.config.ConfigTracker.INSTANCE.forceUnload();
    }
}
''')
        mixins.append("ForgeConfigShutdown")
    (classes / "tandemount-names.properties").write_text("\n".join(k + "=" + v for k, v in sorted(table.items())) + "\n")
    mixin_config = {"required": True, "minVersion": "0.8", "package": "dev.eyecrasher.tandemount.modern.mixin", "compatibilityLevel": "JAVA_8", "plugin": "dev.eyecrasher.tandemount.modern.NativeHooks", "mixins": mixins, "injectors": {"defaultRequire": 1}}
    (classes / "tandemount.mixins.json").write_text(json.dumps(mixin_config, indent=2) + "\n")
    mod = {"schemaVersion": 1, "id": "tandemount", "version": "1.0.0", "name": "Tandemount", "description": "Independent server-side tandem horse riding.", "authors": ["EyeCrasher"], "license": "GPL-3.0-only", "environment": "server", "entrypoints": {"main": ["dev.eyecrasher.tandemount.modern.FabricEntrypoint"]}, "mixins": ["tandemount.mixins.json"], "depends": {"fabricloader": ">=" + target["platforms"]["fabric"]["loader"], "minecraft": "=" + version, "java": ">=" + str(target["java"])}}
    if platform == "fabric":
        (classes / "fabric.mod.json").write_text(json.dumps(mod, indent=2) + "\n")
    else:
        legacy_neo = platform == "neoforge" and version == "1.20.1"
        annotation = "net.minecraftforge.fml.common.Mod" if platform == "forge" or legacy_neo else "net.neoforged.fml.common.Mod"
        adapter = work / "generated" / "dev" / "eyecrasher" / "tandemount" / "modern" / "LoaderEntrypoint.java"
        adapter.write_text('package dev.eyecrasher.tandemount.modern;\n@' + annotation + '("tandemount") public final class LoaderEntrypoint { public LoaderEntrypoint() { LoaderCompatibility.initialize(); TandemRuntime.initialize(); } }\n')
        (classes / "META-INF").mkdir(exist_ok=True)
        forge_id = "forge" if platform == "forge" or legacy_neo else "neoforge"
        metadata_name = "neoforge.mods.toml" if platform == "neoforge" and not legacy_neo and tuple(map(int, version.split("."))) >= (1,20,5) else "mods.toml"
        loader_range = "[1,)" # The SDK is pinned externally; the Minecraft dependency is exact.
        toml = f'''modLoader="javafml"
loaderVersion="{loader_range}"
license="GPL-3.0-only"
[[mods]]
modId="tandemount"
version="1.0.0"
displayName="Tandemount"
authors="EyeCrasher"
displayTest="IGNORE_ALL_VERSION"
description="Independent server-side tandem horse riding."
[[dependencies.tandemount]]
modId="minecraft"
mandatory=true
type="required"
versionRange="[{version}]"
ordering="NONE"
side="SERVER"
[[mixins]]
config="tandemount.mixins.json"
'''
        (classes / "META-INF" / metadata_name).write_text(toml)
        (classes / "META-INF" / "MANIFEST.MF").write_text("Manifest-Version: 1.0\nMixinConfigs: tandemount.mixins.json\n\n")
    sources = [p for p in (ROOT / "mods" / "modern" / "src" / "main" / "java").rglob("*.java") if platform == "fabric" or p.name != "FabricEntrypoint.java"] + list((work / "generated").rglob("*.java"))
    compiler = Path(os.environ.get("TANDEMOUNT_JAVA") or java_for(max(21, target["java"]))).with_name("javac")
    subprocess.run([str(compiler), "-proc:none", "--release", "8", "-cp", os.pathsep.join(map(str, sdk)), "-d", str(classes), *map(str, sources)], check=True)
    out = ROOT / "mods" / "modern" / "build" / f"tandemount-1.0.0-{platform}-{version}.jar"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        def write_entry(name, data):
            info = zipfile.ZipInfo(str(name).replace(os.sep, "/"), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
        for file in sorted(classes.rglob("*")):
            if file.is_file():
                write_entry(file.relative_to(classes), file.read_bytes())
        license_file = ROOT / "LICENSE"
        if license_file.exists():
            write_entry("LICENSE", license_file.read_bytes())
    record = {"minecraft": version, "platform": platform, "artifact": str(out.relative_to(ROOT)), "sha256": hashlib.sha256(out.read_bytes()).hexdigest(), "evidence": "compiled-against-real-loader-sdk", "native": "not-run", "visual": "not-run"}
    (work / "artifact.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record), flush=True)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", default="1.21.1")
    parser.add_argument("--platform", choices=("fabric", "forge", "neoforge"), default="fabric")
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    targets = json.loads((ROOT / "platforms.json").read_text())["targets"]
    for target in targets:
        if target["minecraft"] in ("1.7.10", "1.8.9", "1.12.2"):
            continue
        if (args.all or target["minecraft"] == args.minecraft) and target["platforms"][args.platform]["available"]:
            build(target, args.platform)


if __name__ == "__main__":
    main()
