package org.modhost.jar;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.modhost.Mod;
import org.modhost.ModContext;

/**
 * Lets a class that knows nothing about Mod Host run as a mod. The adapter looks for the
 * usual start-up conventions and calls whichever it finds, so a jar does not need to be
 * rewritten against {@code org.modhost.Mod}.
 *
 * <p>Start-up, first match wins: {@code onLoad(ModContext)}, Fabric's {@code onInitialize()}
 * / {@code onInitializeClient()}, then a public no-argument {@code onEnable}, {@code onLoad},
 * {@code init}, {@code initialize}, {@code start}, {@code setup}, {@code enable} or
 * {@code run}, and finally a static {@code main(String[])}, which runs on its own thread.
 *
 * <p>Before start-up the adapter hands over the {@link ModContext} if the class has a
 * static {@code setContext(ModContext)} / {@code setModContext(ModContext)} method or an
 * instance one with the same name.
 *
 * <p>Shut-down: {@code onUnload}, {@code onDisable}, {@code stop}, {@code shutdown},
 * {@code destroy}, or {@code close()} for an {@link AutoCloseable}.
 */
public final class ModAdapter implements Mod {
    private static final String[] START_NAMES = {"onEnable", "onLoad", "init", "initialize", "start", "setup",
            "enable", "run"};
    private static final String[] FABRIC_NAMES = {"onInitialize", "onInitializeClient", "onInitializeServer"};
    private static final String[] STOP_NAMES = {"onUnload", "onDisable", "stop", "shutdown", "destroy"};

    private final Class<?> type;
    private final String modId;
    private Object instance;
    private Thread mainThread;

    public ModAdapter(Class<?> type, String modId) {
        this.type = type;
        this.modId = modId;
    }

    /** Describes what start-up call the adapter would make for this class, or null if there is none. */
    public static String describeStart(Class<?> type) {
        Method m = findStart(type);
        if (m != null) {
            return type.getName() + "." + m.getName() + "(" + (m.getParameterTypes().length == 1 ? "ModContext" : "")
                    + ")";
        }
        Method main = findMain(type);
        return main != null ? type.getName() + ".main(String[])" : null;
    }

    @Override
    public void onLoad(ModContext ctx) throws Exception {
        boolean needsInstance = findStart(type) != null && !isStatic(findStart(type));
        if (needsInstance || findContextSetter(type) != null || findStart(type) == null && findMain(type) == null) {
            instance = construct(type);
        }
        Method setter = findContextSetter(type);
        if (setter != null) {
            invoke(setter, isStatic(setter) ? null : instance, ctx);
        }
        Method start = findStart(type);
        if (start != null) {
            Object target = isStatic(start) ? null : instance;
            if (start.getParameterTypes().length == 1) {
                invoke(start, target, ctx);
            } else {
                invoke(start, target);
            }
            ctx.log("started through " + type.getSimpleName() + "." + start.getName() + "()");
            return;
        }
        Method main = findMain(type);
        if (main != null) {
            mainThread = new Thread(() -> {
                try {
                    invoke(main, null, (Object) new String[0]);
                } catch (Throwable t) {
                    ctx.error("main() failed: " + t);
                }
            }, "modhost-" + modId + "-main");
            mainThread.setDaemon(true);
            mainThread.start();
            ctx.log("started through " + type.getSimpleName() + ".main(String[]) on its own thread");
            return;
        }
        if (instance == null) {
            throw new IllegalStateException(type.getName() + " has no start-up method the host recognises");
        }
        ctx.warn(type.getSimpleName() + " was created but has no start-up method; nothing was called");
    }

    @Override
    public void onUnload() {
        if (mainThread != null) {
            mainThread.interrupt();
        }
        if (instance == null) {
            return;
        }
        for (String name : STOP_NAMES) {
            Method m = findNoArg(type, name);
            if (m != null) {
                try {
                    invoke(m, isStatic(m) ? null : instance);
                } catch (Throwable ignored) {
                    // the host already logs unload failures it can see; a failing stop is not fatal
                }
                return;
            }
        }
        if (instance instanceof AutoCloseable) {
            try {
                ((AutoCloseable) instance).close();
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
    }

    // ------------------------------------------------------------ lookup

    private static Method findStart(Class<?> type) {
        Method m = findPublic(type, "onLoad", ModContext.class);
        if (m != null) {
            return m;
        }
        for (String name : FABRIC_NAMES) {
            m = findNoArg(type, name);
            if (m != null) {
                return m;
            }
        }
        for (String name : START_NAMES) {
            m = findNoArg(type, name);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Method findContextSetter(Class<?> type) {
        for (String name : new String[] {"setContext", "setModContext"}) {
            Method m = findPublic(type, name, ModContext.class);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Method findMain(Class<?> type) {
        Method m = findPublic(type, "main", String[].class);
        return m != null && isStatic(m) ? m : null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        return findPublic(type, name);
    }

    private static Method findPublic(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (LinkageError e) {
            return null; // a parameter type is missing from this runtime
        }
    }

    private static boolean isStatic(Method m) {
        return Modifier.isStatic(m.getModifiers());
    }

    private static Object construct(Class<?> type) throws Exception {
        try {
            java.lang.reflect.Constructor<?> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " needs a no-argument constructor", e);
        }
    }

    private static void invoke(Method m, Object target, Object... args) throws Exception {
        try {
            m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return e;
    }
}
