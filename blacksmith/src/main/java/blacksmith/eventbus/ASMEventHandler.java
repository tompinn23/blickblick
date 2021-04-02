package blacksmith.eventbus;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

public class ASMEventHandler implements IEventListener {
    private static final AtomicInteger IDs = new AtomicInteger();
    private static final String HANDLER_DESC = Type.getInternalName(IEventListener.class);
    private static final String HANDLER_FUNC_DESC = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Event.class));
    private static final ASMClassLoader LOADER = new ASMClassLoader();
    private static final HashMap<Method, Class<?>> cache = new HashMap<>();

    private final IEventListener handler;
    private final SubscribeEvent subInfo;
    private String readable;
    private java.lang.reflect.Type filter = null;
    

    public ASMEventHandler(Object target, Method method, boolean isGeneric) throws IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        if(Modifier.isStatic(method.getModifiers()))
            handler = (IEventListener)createWrapper(method).newInstance();
        else
            handler =  (IEventListener)createWrapper(method).getConstructor(Object.class).newInstance(target);
        subInfo = method.getAnnotation(SubscribeEvent.class);
        readable = "ASM: " + target + " " + method.getName() + Type.getMethodDescriptor(method);
        if (isGeneric)
        {
            java.lang.reflect.Type type = method.getGenericParameterTypes()[0];
            if (type instanceof ParameterizedType)
            {
                filter = ((ParameterizedType)type).getActualTypeArguments()[0];
                if (filter instanceof ParameterizedType) // Unlikely that nested generics will ever be relevant for event filtering, so discard them
                {
                    filter = ((ParameterizedType)filter).getRawType();
                }
                else if (filter instanceof WildcardType)
                {
                    // If there's a wildcard filter of Object.class, then remove the filter.
                    final WildcardType wfilter = (WildcardType) filter;
                    if (wfilter.getUpperBounds().length == 1 && wfilter.getUpperBounds()[0] == Object.class && wfilter.getLowerBounds().length == 0) {
                        filter = null;
                    }
                }
            }
        }
    }


    @Override
    public void invoke(Event event) {
        if(handler != null) {
            if(!event.isCancelable() || !event.isCanceled() || subInfo.recieveCanceled()) {
                if(filter == null || filter == ((IGenericEvent<T>)event).getGenericType()) {
                    handler.invoke(event);
                }
            }
        }
    }



    private Class<?> createWrapper(Method method) {
        if(cache.containsKey(method))
            return cache.get(method);

        ClassWriter writer = new ClassWriter(0);
        MethodVisitor methodVisitor;
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        String name = getUniqueName(method);
        String desc = name.replace('.', '/');
        String instType = Type.getInternalName(method.getDeclaringClass());
        String eventType = Type.getInternalName(method.getParameterTypes()[0]);

        writer.visit(V1_6, ACC_PUBLIC | ACC_SUPER, desc, null, "java/lang/Object", new String[]{HANDLER_DESC});
        writer.visitSource(".dynamic", null);
        {
            if(!isStatic)
                writer.visitField(ACC_PUBLIC, "instance", "Ljava/lang/Object;", null, null).visitEnd();
        }
        {
            methodVisitor = writer.visitMethod(ACC_PUBLIC, "<init>", isStatic ? "()V" : "(Ljava/lang/Object;)V", null, null);
            methodVisitor.visitCode();
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            if(!isStatic) {
                methodVisitor.visitVarInsn(ALOAD, 0);
                methodVisitor.visitVarInsn(ALOAD, 1);
                methodVisitor.visitFieldInsn(PUTFIELD, desc, "instance", "Ljava/lang/Object;");
            }
            methodVisitor.visitInsn(RETURN);
            methodVisitor.visitMaxs(2, 2);
            methodVisitor.visitEnd();
        }
        {
            methodVisitor = writer.visitMethod(ACC_PUBLIC, "invoke", HANDLER_FUNC_DESC, null, null);
            methodVisitor.visitCode();
            methodVisitor.visitVarInsn(ALOAD, 0);
            if (!isStatic)
            {
                methodVisitor.visitFieldInsn(GETFIELD, desc, "instance", "Ljava/lang/Object;");
                methodVisitor.visitTypeInsn(CHECKCAST, instType);
            }
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitTypeInsn(CHECKCAST, eventType);
            methodVisitor.visitMethodInsn(isStatic ? INVOKESTATIC : INVOKEVIRTUAL, instType, method.getName(), Type.getMethodDescriptor(method), false);
            methodVisitor.visitInsn(RETURN);
            methodVisitor.visitMaxs(2, 2);
            methodVisitor.visitEnd();
        }
        writer.visitEnd();
        Class<?> ret = LOADER.define(name, writer.toByteArray());
        cache.put(method, ret);
        return ret;
    }

    private String getUniqueName(Method callback)
    {
        return String.format("%s_%d_%s_%s_%s", getClass().getName(), IDs.getAndIncrement(),
                callback.getDeclaringClass().getSimpleName(),
                callback.getName(),
                callback.getParameterTypes()[0].getSimpleName());
    }


    @Override
    public String toString() {
        return readable;
    }

    public EventPriority getPriority() {
        return subInfo.eventPriority();
    }

    private static class ASMClassLoader extends ClassLoader
    {
        private ASMClassLoader()
        {
            super(null);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            return Class.forName(name, resolve, Thread.currentThread().getContextClassLoader());
        }

        Class<?> define(String name, byte[] data)
        {
            return defineClass(name, data, 0, data.length);
        }
    }

}
