package org.modhost.jar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the inspector knows about one class, read from a .class file or a dex file. */
public final class ClassInfo {
    /** Dotted name, e.g. "com.example.MyMod". */
    public final String name;
    public final String superName;
    public final List<String> interfaces;
    /** "name(descriptor)" strings, such as "onLoad(Lorg/modhost/ModContext;)V". May be empty for dex files. */
    public final List<String> methods;
    public final boolean isPublic;
    public final boolean isAbstract;
    public final boolean isInterface;
    public final boolean hasPublicNoArgConstructor;

    public ClassInfo(String name, String superName, List<String> interfaces, List<String> methods, boolean isPublic,
            boolean isAbstract, boolean isInterface, boolean hasPublicNoArgConstructor) {
        this.name = name;
        this.superName = superName;
        this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        this.methods = Collections.unmodifiableList(new ArrayList<>(methods));
        this.isPublic = isPublic;
        this.isAbstract = isAbstract;
        this.isInterface = isInterface;
        this.hasPublicNoArgConstructor = hasPublicNoArgConstructor;
    }

    public boolean implementsInterface(String dottedName) {
        return interfaces.contains(dottedName);
    }

    /** True when a method with this name exists, whatever its parameters. */
    public boolean hasMethod(String methodName) {
        String prefix = methodName + "(";
        for (String m : methods) {
            if (m.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMethod(String methodName, String descriptor) {
        return methods.contains(methodName + descriptor);
    }

    /** Whether this class can be created by the host: public, concrete, with a public no-argument constructor. */
    public boolean instantiable() {
        return isPublic && !isAbstract && !isInterface && hasPublicNoArgConstructor;
    }

    @Override
    public String toString() {
        return name;
    }
}
