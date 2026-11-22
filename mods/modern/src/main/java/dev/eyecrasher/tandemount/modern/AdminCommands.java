package dev.eyecrasher.tandemount.modern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Vanilla Brigadier commands; admin uses native operator permission level 2. */
public final class AdminCommands {
    private AdminCommands() {}
    @SuppressWarnings("unchecked") public static void register(Object server) throws Exception {
        Object commands = Names.call("server.commands", server);
        CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) Names.call("commands.dispatcher", commands);
        LiteralArgumentBuilder<Object> root = LiteralArgumentBuilder.literal("tandemount");
        root.executes(context -> execute(context.getSource(), "help"));
        root.then(RequiredArgumentBuilder.<Object, String>argument("action", StringArgumentType.greedyString())
            .executes(context -> execute(context.getSource(), StringArgumentType.getString(context, "action"))));
        dispatcher.register(root);
    }
    public static int execute(Object source, String command) {
        String[] args = command.trim().isEmpty() ? new String[0] : command.trim().split("\\s+");
        try {
            if (args.length == 0 || args[0].equals("help")) {
                send(source, styled("Tandemount — shared horse riding", 0x55FFFF));
                message(source, "Right-click an occupied adult, tamed, saddled horse to join. Use the normal dismount key to leave.");
                message(source, "Admin: /tandemount status | height <0..3> | rear <0..2> | height +/- | rear +/- | enabled <true|false> | debug <true|false> | exclusions <dimension,...|clear> | save | reload | reset");
                if (allowed(source)) {
                    for (Object control : controls()) send(source, control);
                    message(source, "Height=" + TandemRuntime.SETTINGS.height + ", rear=" + TandemRuntime.SETTINGS.rear + " blocks; step=1/16.");
                }
                return 1;
            }
            if (!allowed(source)) { message(source, "You do not have permission tandemount.admin (operator level 2 on this loader)."); return 0; }
            Settings active = TandemRuntime.SETTINGS;
            if (args.length == 1 && args[0].equals("status")) {
                message(source, "Tandemount: enabled=" + active.enabled + ", debug=" + active.debug + ", height=" + active.height + ", rear=" + active.rear + " blocks; horse-types=horse; excluded-dimensions=" + (active.excludedDimensions.isEmpty() ? "(none)" : active.excludedText()) + "; active-rides=" + TandemRuntime.rideCount() + ".");
                return 1;
            }
            Settings settings = active.copy();
            if (args[0].equals("height") || args[0].equals("rear")) {
                if (args.length != 2) throw new IllegalArgumentException("Specify a value, +, or -");
                boolean height = args[0].equals("height");
                double current = height ? settings.height : settings.rear;
                String value = args[1];
                if (value.equals("+") || value.equals("-")) value = Double.toString(current + (value.equals("+") ? 0.0625 : -0.0625));
                double number = Settings.bounded(value, 0, height ? 3 : 2);
                if (height) settings.height = number; else settings.rear = number;
                settings.save();
            } else if (args.length == 1 && (args[0].equals("enable") || args[0].equals("disable"))) {
                settings.enabled = args[0].equals("enable"); settings.save();
            } else if (args.length == 1 && args[0].equals("reload")) {
                settings.load();
            } else if (args.length == 1 && args[0].equals("save")) {
                settings.save();
            } else if (args.length == 1 && args[0].equals("reset")) {
                settings.enabled = true; settings.debug = false; settings.height = Settings.DEFAULT_HEIGHT; settings.rear = Settings.DEFAULT_REAR;
                settings.excludedDimensions = java.util.Collections.emptySet(); settings.save();
            } else if (args.length == 2 && args[0].equals("debug") && (args[1].equals("true") || args[1].equals("false"))) {
                settings.debug = Boolean.parseBoolean(args[1]); settings.save();
            } else if (args.length == 2 && args[0].equals("enabled") && (args[1].equals("true") || args[1].equals("false"))) {
                settings.enabled = Boolean.parseBoolean(args[1]); settings.save();
            } else if (args.length == 2 && args[0].equals("exclusions")) {
                settings.excludedDimensions = Settings.exclusions(args[1].equals("clear") ? "" : args[1]); settings.save();
            } else throw new IllegalArgumentException("Unknown command; use /tandemount help");
            active.replace(settings);
            TandemRuntime.applySettings();
            message(source, "Tandemount settings saved. Height=" + settings.height + ", rear=" + settings.rear + ", enabled=" + settings.enabled + ".");
            return 1;
        } catch (Exception exception) {
            try { message(source, "Settings unchanged by invalid input. Use /tandemount help."); }
            catch (Exception ignored) { TandemRuntime.error("Command response", ignored); }
            return 0;
        }
    }
    public static boolean allowed(Object source) throws Exception {
        if (Names.has("source.permission")) return Boolean.TRUE.equals(Names.call("source.permission", source, 2));
        return Boolean.TRUE.equals(Names.call("permissions.has", Names.call("source.permissions", source), Names.field("Permissions.COMMANDS_GAMEMASTER", null)));
    }
    private static Object component(String text) throws Exception {
        if (Names.has("component.literal")) return Names.call("component.literal", null, text);
        Class<?> literal = Class.forName(Names.TABLE.getProperty("c.TextComponent"));
        return literal.getConstructor(String.class).newInstance(text);
    }
    public static void message(Object source, String text) throws Exception { send(source, component(text)); }
    private static void send(Object source, Object component) throws Exception {
        Method method = Names.method("source.message");
        if (method.getParameterCount() == 2) method.invoke(source, component, false);
        else method.invoke(source, component);
    }
    static Object styled(String text, int color) throws Exception {
        Object component = component(text);
        Object style = Names.call("component.style", component);
        Object nativeColor = Names.method("style.color").getParameterTypes()[0] == int.class ? color : Names.call("color.rgb", null, color);
        Names.call("component.setStyle", component, Names.call("style.color", style, nativeColor));
        return component;
    }
    public static List<Object> controls() throws Exception {
        List<Object> controls = new ArrayList<Object>();
        String[][] values = {{"Height [-]", "height -"}, {"Height [+]", "height +"},
                {"Rear [-]", "rear -"}, {"Rear [+]", "rear +"},
                {"Save current settings", "save"}, {"Reload saved settings", "reload"}, {"Reset defaults", "reset"},
                {"Show active settings", "status"},
                {TandemRuntime.SETTINGS.debug ? "Disable debug" : "Enable debug", "debug " + !TandemRuntime.SETTINGS.debug},
                {TandemRuntime.SETTINGS.enabled ? "Disable riding" : "Enable riding", TandemRuntime.SETTINGS.enabled ? "disable" : "enable"}};
        for (String[] value : values) {
            Object component = styled(value[0] + " ", value[1].equals("reset") ? 0xFFAA00 : 0x55FF55);
            // These are the exact upstream signatures emitted by the build tool.
            Object style = Names.call("component.style", component);
            Object click;
            Class<?> clickType = Names.type("ClickEvent");
            if (Names.TABLE.containsKey("c.RunCommand")) {
                click = Names.type("RunCommand").getConstructor(String.class).newInstance("/tandemount " + value[1]);
            } else {
                Object action = Names.field("ClickAction.RUN_COMMAND", null);
                click = clickType.getConstructor(Names.type("ClickAction"), String.class).newInstance(action, "/tandemount " + value[1]);
            }
            style = Names.call("style.click", style, click);
            Names.call("component.setStyle", component, style);
            controls.add(component);
        }
        return controls;
    }
}
