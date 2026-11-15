/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 EyeCrasher. Test-only; never packaged in a release JAR. */
package dev.eyecrasher.tandemount.clienttest;
import java.net.*;
import java.nio.file.*;
public final class AttachmentClientLauncher {
 public static void main(String[] args)throws Exception{
  try(var loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},AttachmentClientLauncher.class.getClassLoader())){
   Class<?> main=Class.forName("AttachmentClientProbe",true,loader);
   try{main.getMethod("main",String[].class).invoke(null,(Object)java.util.Arrays.copyOfRange(args,1,args.length));}
   catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}
  }
 }
}
