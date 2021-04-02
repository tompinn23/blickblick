package blacksmith.eventbus;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class AnnotationScanner extends ClassVisitor {

    public AnnotationScanner() {
        super(Opcodes.ASM8);
    }


    static class MyAnnotationVisitor extends AnnotationVisitor {
        MyAnnotationVisitor() {
            super(Opcodes.ASM8);
        }

        @Override
        public void visit(String name, Object value) {
            System.out.println("annotation: " + name + "=" + value);
            super.visit(name, value);
        }
    }

    static class MyMethodVisitor extends MethodVisitor {
        MyMethodVisitor() {
            super(Opcodes.ASM8);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            System.out.println("annotation type: " + desc);
            return new MyAnnotationVisitor();
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        System.out.println("method: name = " + name);
        return new MyMethodVisitor();
    }
}
