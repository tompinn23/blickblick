package blacksmith.classloader;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import sun.security.util.ManifestEntryVerifier;

public class SecureJarHandler {

    private static final Class<?> JVCLASS = LamdbaExceptionUtils.uncheck(() -> Class.forName("java.util.jar.JarVerifier"));
    private static final Method BEGIN_ENTRY = LamdbaExceptionUtils.uncheck(() -> JVCLASS.getMethod("beginEntry", JarEntry.class, ManifestEntryVerifier.class));
    private static final Method UPDATE = LamdbaExceptionUtils.uncheck(() -> JVCLASS.getMethod("update", int.class, byte[].class, int.class, ManifestEntryVerifier.class))


    public static CodeSource createCodeSource(final String path, @Nullable final URL url, final byte[] classBytes, @Nullable final Manifest jarManifest) {
        if(JV == null) return null;
        if(manifest == null) return null;
        if(url == null) return null;
    }
}
