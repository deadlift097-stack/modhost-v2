package org.modhost.jar;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads just enough of a Java .class file to describe it: names, interfaces, methods and
 * whether it mentions Minecraft Java classes. It never loads or runs the class.
 */
public final class ClassFileReader {
    private ClassFileReader() {}

    /** Result of reading one class file. */
    public static final class Result {
        public final ClassInfo info;
        /** True when the constant pool names a class from net/minecraft/ or com/mojang/. */
        public final boolean usesMinecraftJava;

        Result(ClassInfo info, boolean usesMinecraftJava) {
            this.info = info;
            this.usesMinecraftJava = usesMinecraftJava;
        }
    }

    public static Result read(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort(); // minor
        int major = in.readUnsignedShort();
        if (major < 45 || major > 80) {
            throw new IOException("unsupported class file version " + major);
        }
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] classRef = new int[count];
        boolean mc = false;
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: {
                    utf[i] = in.readUTF();
                    if (utf[i].startsWith("net/minecraft/") || utf[i].startsWith("Lnet/minecraft/")
                            || utf[i].startsWith("com/mojang/") || utf[i].startsWith("Lcom/mojang/")) {
                        mc = true;
                    }
                    break;
                }
                case 7:
                    classRef[i] = in.readUnsignedShort();
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    in.readUnsignedShort();
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++; // takes two slots
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("bad constant pool tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        String name = className(utf, classRef, in.readUnsignedShort());
        int superIdx = in.readUnsignedShort();
        String superName = superIdx == 0 ? null : className(utf, classRef, superIdx);
        int ifaceCount = in.readUnsignedShort();
        List<String> ifaces = new ArrayList<>();
        for (int i = 0; i < ifaceCount; i++) {
            ifaces.add(className(utf, classRef, in.readUnsignedShort()));
        }
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
        int methodCount = in.readUnsignedShort();
        List<String> methods = new ArrayList<>();
        boolean noArgCtor = false;
        for (int i = 0; i < methodCount; i++) {
            int macc = in.readUnsignedShort();
            String mname = utf[in.readUnsignedShort()];
            String mdesc = utf[in.readUnsignedShort()];
            skipAttributes(in);
            methods.add(mname + mdesc);
            if ("<init>".equals(mname) && "()V".equals(mdesc) && (macc & 0x0001) != 0) {
                noArgCtor = true;
            }
        }
        ClassInfo info = new ClassInfo(name, superName, ifaces, methods, (access & 0x0001) != 0,
                (access & 0x0400) != 0, (access & 0x0200) != 0, noArgCtor);
        return new Result(info, mc);
    }

    private static String className(String[] utf, int[] classRef, int idx) throws IOException {
        if (idx <= 0 || idx >= classRef.length || classRef[idx] == 0 || utf[classRef[idx]] == null) {
            throw new IOException("bad class reference");
        }
        return utf[classRef[idx]].replace('/', '.');
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
            int len = in.readInt();
            if (len < 0) {
                throw new IOException("bad attribute length");
            }
            int left = len;
            while (left > 0) {
                int skipped = in.skipBytes(left);
                if (skipped <= 0) {
                    throw new IOException("truncated class file");
                }
                left -= skipped;
            }
        }
    }
}
