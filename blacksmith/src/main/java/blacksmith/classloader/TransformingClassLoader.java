package blacksmith.classloader;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static blacksmith.eventbus.LamdbaExceptionUtils.rethrowFunction;

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
    private final URL[] specialJars;

    public TransformingClassLoader(Path... paths) {
        this.classTransformer = new ClassTransformer(this);
        this.specialJars = Arrays.stream(paths).map(rethrowFunction(path -> path.toUri().toURL())).toArray(URL[]::new);
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


    static class AutoURLConnection implements AutoCloseable {
        private final URLConnection urlConnection;
        private final InputStream inputStream;
        private final Function<URLConnection, Manifest> manifestFinder;

        AutoURLConnection(URL url, Function<URLConnection, Manifest> manifestFinder) throws IOException {
            this.urlConnection = url.openConnection();
            this.inputStream = this.urlConnection.getInputStream();
            this.manifestFinder = manifestFinder;
        }

        @Override
        public void close() throws IOException {
            this.inputStream.close();
        }

        int getContentLength() {
            return this.urlConnection.getContentLength();
        }

        InputStream getInputStream() {
            return this.inputStream;
        }

        Manifest getJarManifest() {
            return manifestFinder.apply(this.urlConnection);
        }

        URL getBaseUrl() {
            return this.urlConnection.getURL();
        }
    }

    private static class DelegatedClassLoader extends URLClassLoader {

        private final TransformingClassLoader tcl;

        public DelegatedClassLoader(TransformingClassLoader transformingClassLoader) {
            super(transformingClassLoader.specialJars, null);
            this.tcl = transformingClassLoader;
        }

        public Map.Entry<byte[], CodeSource> findClass(String name, Function<String, Enumeration<URL>> classBytesFinder, String classloadingReason) throws ClassNotFoundException {
            final String path = name.replace('.', '/').concat(".class");
            final URL classResource = EnumerationHelper.firstElementOrNull(classBytesFinder.apply(path));
            byte[] classBytes;
            CodeSource codeSource = null;
            Manifest jarManifest = null;
            URL baseUrl = null;
            if(classResource != null) {
                try(AutoURLConnection urlConnection = new AutoURLConnection(classResource, tcl.manifestFinder)) {
                    final int length = urlConnection.getContentLength();
                    final InputStream is = urlConnection.getInputStream();
                    classBytes = new byte[length];
                    int pos = 0, remain = length, read;
                    while ((read = is.read(classBytes, pos, remain)) != -1 && remain > 0) {
                        pos += read;
                        remain -= read;
                    }
                    jarManifest = urlConnection.getJarManifest();
                    baseUrl = urlConnection.getBaseUrl();
                } catch (IOException e) {
                    LOGGER.trace(CLASSLOADING, "Failed to load bytes for class {} at {} reason {}", name, classResource, classloadingReason, e);
                    throw new ClassNotFoundException("Failed to find class bytes for " + name, e);
                }
            } else {
                classBytes = new byte[0];
            }
            final byte[] processedClassBytes = tcl.classTransformer.transform(classBytes, name, classloadingReason);
            if(processedClassBytes.length > 0) {
                LOGGER.trace(CLASSLOADING, "Loading transform target {} from {} reason {}", name, classResource, classloadingReason);

                if(classloadingReason.equals(ITransformerActivity.CLASSLOADING_REASON)) {
                    int i = name.lastIndexOf('.');
                    String pkgname = i > 0 ? name.substring(0, i) : "";
                    tryDefinePackage(pkgname, jarManifest);
                    codeSource = SecureJarHandler.createCodeSource(path, baseUrl, classBytes, jarManifest);
                }

                return new AbstractMap.SimpleImmutableEntry<>(processedClassBytes, codeSource);
            } else {
                LOGGER.trace(CLASSLOADING, "Failed to transform target {} from {}", name, classResource);
                throw new ClassNotFoundException();
            }
        }

        Package tryDefinePackage(String name, @Nullable Manifest man) {
            if(tcl.getPackage(name) == null) {
                synchronized (this) {
                    if(tcl.getPackage(name) != null) return tcl.getPackage(name);

                    String path = name.replace('.', '/').concat("/");
                    String specTitle = null, specVersion = null, specVendor = null;
                    String implTitle = null, implVersion = null, implVendor = null;

                    if(man != null) {
                        Attributes attr = man.getAttributes(path);
                        if(attr != null) {
                            specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                            specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                            specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                            implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                            implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                            implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                        }
                        attr = man.getMainAttributes();
                        if (attr != null) {
                            if (specTitle == null) {
                                specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                            }
                            if (specVersion == null) {
                                specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                            }
                            if (specVendor == null) {
                                specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                            }
                            if (implTitle == null) {
                                implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                            }
                            if (implVersion == null) {
                                implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                            }
                            if (implVendor == null) {
                                implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                            }
                        }
                    }
                    return tcl.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, null);
                }
            } else {
                return  tcl.getPackage(name);
            }
        }

    }
}
