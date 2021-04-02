package blacksmith.eventbus;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class TransformingClassLoader extends ClassLoader {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Marker CLASSLOADING = MarkerManager.getMarker("CLASSLOADING");

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final List<String> SKIP_PACKAGE_PREFIXES = Arrays.asList(
            "java.", "javax.", "org.objectweb.asm.", "org.apache.logging.log4j."
    );

    private final ClassTransformer classTransformer;
    private final Predicate<String> targetPackageFilter;
    private DelegatedClassLoader delegatedClassLoader;
    private Function<String, Enumeration<URL>> resourceFinder;

    public TransformingClassLoader() {
        this.classTransformer = new ClassTransformer(this);
        this.delegatedClassLoader = new DelegatedClassLoader(this);
        this.targetPackageFilter = s -> SKIP_PACKAGE_PREFIXES.stream().noneMatch(s::startsWith);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if(!targetPackageFilter.test(name)) {
                LOGGER.trace(CLASSLOADING, "Delegating to parent {}", name);
                return super.loadClass(name, resolve);
            }
            try {
                LOGGER.trace(CLASSLOADING, "Attempting to load {}", name);
                final Class<?> loadedClass = loadClass(name, this.resourceFinder);
            }
        }
    }

    private Class<?> getLoadedClass(String name) {
        return findLoadedClass(name);
    }

    public Class<?> loadClass(String name, Function<String,Enumeration<URL>> classBytesFinder) {
        final Class<?> exisitingClass = getLoadedClass(name);
        if(exisitingClass != null) {
            LOGGER.trace(CLASSLOADING, "Found existing class {}", name);
            return exisitingClass;
        }
        final Map.Entry<byte[], CodeSource> classData = delegatedClassLoader.findClass(name, classBytesFinder, ITransformerActivity.CLASSLOADING_REASON);
    }



    private static class DelegatedClassLoader extends URLClassLoader {

        private final TransformingClassLoader tcl;

        public DelegatedClassLoader(TransformingClassLoader transformingClassLoader) {
            super()
            this.tcl = transformingClassLoader;
        }

        public Map.Entry<byte[], CodeSource> findClass(String name, Function<String, Enumeration<URL>> classBytesFinder, String classloadingReason) {
        }
    }
}
