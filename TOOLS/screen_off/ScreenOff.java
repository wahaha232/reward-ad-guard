import java.lang.reflect.Method;

/**
 * Turns the phone's display panel off (or back on) while the system keeps
 * running, like scrcpy's --turn-screen-off: Android still treats the screen
 * as on, so the auto-collector keeps taking screenshots and tapping, and the
 * phone does not lock. Pressing the power button turns the panel on again.
 *
 * Run as the shell user through app_process (TOOLS/screen_off/screen_off.ps1):
 *   CLASSPATH=/data/local/tmp/screenoff.dex app_process / ScreenOff off|on
 *
 * Android 13 and below: SurfaceControl has the display tokens. Android 14+
 * moved them to com.android.server.display.DisplayControl in services.jar,
 * loaded here the way scrcpy does it. Only reflection, no Android SDK needed
 * to compile.
 */
public final class ScreenOff {

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("off") || args[0].equals("on"))) {
            System.err.println("usage: ScreenOff off|on");
            System.exit(2);
        }
        int mode = args[0].equals("off") ? POWER_MODE_OFF : POWER_MODE_NORMAL;
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        Object[] tokens = displayTokens(surfaceControl);
        if (tokens.length == 0) {
            System.err.println("no display token found");
            System.exit(3);
        }
        int done = 0;
        for (Object token : tokens) {
            if (setPowerMode(surfaceControl, token, mode)) done++;
        }
        if (done == 0) {
            System.err.println("setDisplayPowerMode not available");
            System.exit(4);
        }
        System.out.println("OK " + args[0] + " displays=" + done);
        System.out.flush();
        // Skip the runtime's shutdown: unloading android_servers crashed it
        // (Segmentation fault) after the work was already done.
        Runtime.getRuntime().halt(0);
    }

    private static Class<?> displayControl;

    private static Class<?> displayControl() throws Exception {
        if (displayControl != null) return displayControl;
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class,
            String.class, ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader loader = (ClassLoader) create.invoke(null, "/system/framework/services.jar",
            null, null, null, 0, true, null);
        Class<?> cls = loader.loadClass("com.android.server.display.DisplayControl");
        Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        loadLibrary.setAccessible(true);
        loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers");
        displayControl = cls;
        return cls;
    }

    private static Object[] displayTokens(Class<?> surfaceControl) throws Exception {
        // Android 10-13.
        try {
            Method m = surfaceControl.getMethod("getInternalDisplayToken");
            Object token = m.invoke(null);
            if (token != null) return new Object[] {token};
        } catch (NoSuchMethodException ignored) {
        }
        // Android 14+: every physical display (some phones report two).
        try {
            Class<?> dc = displayControl();
            long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
            Method tokenOf = dc.getMethod("getPhysicalDisplayToken", long.class);
            java.util.List<Object> tokens = new java.util.ArrayList<>();
            for (long id : ids) {
                Object token = tokenOf.invoke(null, id);
                if (token != null) tokens.add(token);
            }
            return tokens.toArray();
        } catch (Throwable t) {
            System.err.println("DisplayControl: " + t);
        }
        return new Object[0];
    }

    private static boolean setPowerMode(Class<?> surfaceControl, Object token, int mode) {
        Class<?> binder;
        try {
            binder = Class.forName("android.os.IBinder");
        } catch (ClassNotFoundException e) {
            return false;
        }
        try {
            surfaceControl.getMethod("setDisplayPowerMode", binder, int.class).invoke(null, token, mode);
            return true;
        } catch (Throwable t) {
            System.err.println("SurfaceControl.setDisplayPowerMode: " + t);
        }
        try {
            displayControl().getMethod("setDisplayPowerMode", binder, int.class).invoke(null, token, mode);
            return true;
        } catch (Throwable t) {
            System.err.println("DisplayControl.setDisplayPowerMode: " + t);
        }
        return false;
    }
}
