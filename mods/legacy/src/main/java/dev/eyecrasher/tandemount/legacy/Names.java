/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher */
package dev.eyecrasher.tandemount.legacy;

import java.io.InputStream;
import java.util.Properties;

/** The build extracts this small symbol table from the exact upstream MCP SDK. */
public final class Names {
    private static final Properties NAMES = new Properties();
    static {
        try (InputStream in = Names.class.getResourceAsStream("/tandemount-names.properties")) {
            if (in == null) throw new IllegalStateException("Missing exact-version symbol table");
            NAMES.load(in);
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    private Names() {}
    public static String canonical(String name) { return NAMES.getProperty(name, name); }
    public static boolean is(String actual, String readable) { return canonical(actual).equals(readable); }
    public static String minecraft() { return NAMES.getProperty("minecraft"); }
}
