/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher. Disposable Paper26.3 regression fixture. */
package dev.eyecrasher.tandemount.diagnosis;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.*;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.entity.CraftEntity;

/** Disposable native portal regression fixture. Never a distribution artifact. */
public final class PortalTransferFixture extends JavaPlugin implements Listener {
 World source,target;Player driver,rider;Horse horse;Entity result;
 Map<String,Object> report=new LinkedHashMap<>();List<Map<String,Object>> events=new ArrayList<>();boolean cancelPortal,cancelPassenger,returning;
 Gson gson=new GsonBuilder().setPrettyPrinting().create();
 public void onEnable(){getServer().getPluginManager().registerEvents(this,this);report.put("portalLinkedInstalled",getServer().getPluginManager().isPluginEnabled("PortalLinked"));write("ready");}
 void write(String phase){report.put("phase",phase);report.put("events",events);try{Files.writeString(getDataFolder().toPath().resolve("state.json"),gson.toJson(report));}catch(Exception e){getDataFolder().mkdirs();try{Files.writeString(getDataFolder().toPath().resolve("state.json"),gson.toJson(report));}catch(Exception x){throw new RuntimeException(x);}}}
 Map<String,Object> state(org.bukkit.entity.Entity e){Map<String,Object> r=new LinkedHashMap<>();if(e==null)return r;Location at=e.getLocation();r.put("id",e.getEntityId());r.put("type",e.getType().toString());r.put("world",e.getWorld().getName());r.put("xyz",List.of(at.getX(),at.getY(),at.getZ()));r.put("vehicle",e.getVehicle()==null?-1:e.getVehicle().getEntityId());r.put("valid",e.isValid());Entity nativeEntity=((CraftEntity)e).getHandle();r.put("nativeClass",nativeEntity.getClass().getName());r.put("yaw",at.getYaw());r.put("pitch",at.getPitch());r.put("portalCooldown",nativeEntity.getPortalCooldown());r.put("nativeArrivalCooldown",nativeEntity.getDimensionChangingDelay());r.put("noPhysics",nativeEntity.noPhysics);r.put("nativeBounds",nativeEntity.getBoundingBox().toString());if(e.getType().toString().equals("INTERACTION")){try{r.put("height",e.getClass().getMethod("getInteractionHeight").invoke(e));}catch(Exception ignored){}}return r;}
 List<Map<String,Object>> tree(org.bukkit.entity.Entity e){List<Map<String,Object>> out=new ArrayList<>();out.add(state(e));for(var child:e.getPassengers())out.addAll(tree(child));return out;}
 Map<String,Object> players(){Map<String,Object> value=new LinkedHashMap<>();value.put("driver",state(driver));value.put("rider",state(rider));try{JavaPlugin subject=(JavaPlugin)Bukkit.getPluginManager().getPlugin("Tandemount");Class<?> bridge=Class.forName("dev.eyecrasher.tandemount.NativeSeat",true,subject.getClass().getClassLoader());var f=bridge.getDeclaredField("RIDES");f.setAccessible(true);value.put("nativeRideEntries",((Map<?,?>)f.get(null)).size());}catch(Exception e){value.put("nativeRideEntriesError",e.toString());}return value;}
 void frame(World w,int x,int y,int z){for(int a=-2;a<=6;a++)for(int b=-3;b<=3;b++){w.getBlockAt(x+a,y-1,z+b).setType(Material.OBSIDIAN,false);for(int dy=0;dy<=6;dy++)w.getBlockAt(x+a,y+dy,z+b).setType(Material.AIR,false);}for(int a=-1;a<=4;a++)for(int b=-1;b<=5;b++)w.getBlockAt(x+a,y+b,z).setType(a==-1||a==4||b==-1||b==5?Material.OBSIDIAN:Material.NETHER_PORTAL,false);}
 void positionTree(Entity vehicle){for(Entity passenger:vehicle.getPassengers()){vehicle.positionRider(passenger);positionTree(passenger);}}
 public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof ConsoleCommandSender)||args.length==0)return true;try{switch(args[0]){
 case "setup":{
  driver=Bukkit.getPlayerExact("PortalDriver");rider=Bukkit.getPlayerExact("PortalRider");if(driver==null||rider==null)throw new IllegalStateException("two sockets required");
  source=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();target=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NETHER).findFirst().orElseThrow();
  frame(source,832,64,8);frame(target,104,64,1);
  driver.teleport(new Location(source,833.5,64,10.5));rider.teleport(new Location(source,833.5,64,11.5));
  horse=source.spawn(new Location(source,833.5,64,10.5,0,0),Horse.class);horse.setAdult();horse.setTamed(true);horse.setOwner(driver);horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));horse.setInvulnerable(true);horse.setAI(false);horse.setGravity(false);
  if(!horse.addPassenger(driver))throw new IllegalStateException("driver native mount refused");
  Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(rider,horse));
  Bukkit.getScheduler().runTaskLater(this,()->{report.put("before",players());report.put("treeBefore",tree(horse));write("mounted");},10);break;
 }
 case "cancel-portal":cancelPortal=true;write("cancel-set");break;
 case "cancel-passenger":cancelPassenger=true;write("cancel-set");break;
 case "look":{
  rider.setRotation(135F,25F);write("look-set");break;
 }
 case "retry":{
  if(result!=null)horse=(Horse)result.getBukkitEntity();
  Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(rider,horse));
  Bukkit.getScheduler().runTaskLater(this,()->{report.put("retry",players());write("remounted");},10);break;
 }
 case "return":returning=true;
 case "travel":{
  Entity nativeHorse=((CraftEntity)horse).getHandle();nativeHorse.snapTo(returning?105.5:833.5,64,returning?1.5:8.5,0,0);positionTree(nativeHorse);
  nativeHorse.setPortalCooldown(300);
  report.put("beforePortal",players());report.put("treeBeforePortal",tree(horse));
  TeleportTransition transition=((NetherPortalBlock)Blocks.NETHER_PORTAL).getPortalDestination((ServerLevel)nativeHorse.level(),nativeHorse,new BlockPos(returning?105:833,64,returning?1:8));
  if(transition==null){report.put("afterCancelled",players());write("complete");break;}
  var exit=PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(nativeHorse),PositionMoveRotation.of(transition),transition.relatives()).position();
  report.put("nativeExit",List.of(exit.x(),exit.y(),exit.z()));report.put("nativeExitWorld",transition.newLevel().getWorld().getName());
  result=nativeHorse.teleport(transition);report.put("afterSynchronous",players());if(result!=null)result.setPortalCooldown(300);((CraftEntity)driver).getHandle().setPortalCooldown(300);if(result!=null)report.put("treeAfterSynchronous",tree(result.getBukkitEntity()));write("transferred");
  Bukkit.getScheduler().runTaskLater(this,()->{report.put("after10Ticks",players());if(result!=null)report.put("treeAfter10Ticks",tree(result.getBukkitEntity()));write("complete");},10);break;
 }
 default:throw new IllegalArgumentException("setup|travel");}
 }catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"PORTAL_DIAGNOSIS",e);report.put("error",e.toString());write("failed");}return true;}
 void event(String kind,PlayerTeleportEvent e){Map<String,Object> r=new LinkedHashMap<>();r.put("kind",kind);r.put("playerId",e.getPlayer().getEntityId());r.put("cause",e.getCause().toString());r.put("cancelled",e.isCancelled());r.put("from",String.valueOf(e.getFrom()));r.put("to",String.valueOf(e.getTo()));r.put("current",state(e.getPlayer()));events.add(r);}
 @EventHandler(priority=EventPriority.HIGHEST)public void cancelEntity(EntityPortalEvent e){if(cancelPortal&&e.getEntity()==horse)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST)public void cancelPlayer(PlayerTeleportEvent e){if(cancelPassenger&&e.getPlayer()==rider&&e.getCause()==PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.LOWEST)public void before(PlayerTeleportEvent e){event("teleport-lowest",e);}
 @EventHandler(priority=EventPriority.MONITOR)public void after(PlayerTeleportEvent e){event("teleport-monitor",e);}
 @EventHandler(priority=EventPriority.MONITOR)public void entityPortal(EntityPortalEvent e){events.add(Map.of("kind","entity-portal","entity",state(e.getEntity()),"from",String.valueOf(e.getFrom()),"to",String.valueOf(e.getTo()),"cancelled",e.isCancelled()));}
 @EventHandler(priority=EventPriority.MONITOR)public void changed(PlayerChangedWorldEvent e){events.add(Map.of("kind","changed-world","entity",state(e.getPlayer()),"oldWorld",e.getFrom().getName()));}
}
