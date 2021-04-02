package one.tlph.blick.registry;

import com.google.common.base.Suppliers;

import java.util.function.Supplier;

public class RegistryObject<T extends IRegistryEntry> {
    private final Supplier<T> objSupplier;

    public RegistryObject(Supplier<T> objSupplier) {
        this.objSupplier = memoize(objSupplier);
    }

    private static <T> java.util.function.Supplier<T> memoize(java.util.function.Supplier<? extends T> supplier) {
        return com.google.common.base.Suppliers.memoize(supplier::get);
    }

    public T get() {
        return objSupplier.get();
    }
}
