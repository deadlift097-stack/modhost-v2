package org.modhost.jar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the classes inside a dex file (the format Android runs) without loading it:
 * names, superclasses, interfaces and method names. Used to find a mod's entry point in
 * jars that were already converted with d8.
 */
public final class DexReader {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int NO_INDEX = -1;

    private DexReader() {}

    public static List<ClassInfo> readClasses(byte[] dex) throws IOException {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
            throw new IOException("not a dex file");
        }
        ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(40) != 0x12345678) {
            throw new IOException("unexpected dex byte order");
        }
        int stringCount = b.getInt(56);
        int stringOff = b.getInt(60);
        int typeCount = b.getInt(64);
        int typeOff = b.getInt(68);
        int methodCount = b.getInt(88);
        int methodOff = b.getInt(92);
        int classCount = b.getInt(96);
        int classOff = b.getInt(100);
        check(dex, stringOff, 4L * stringCount);
        check(dex, typeOff, 4L * typeCount);
        check(dex, methodOff, 8L * methodCount);
        check(dex, classOff, 32L * classCount);

        Map<Integer, String> stringCache = new HashMap<>();
        List<ClassInfo> out = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            int base = classOff + 32 * i;
            int typeIdx = b.getInt(base);
            int access = b.getInt(base + 4);
            int superIdx = b.getInt(base + 8);
            int ifaceOff = b.getInt(base + 12);
            int dataOff = b.getInt(base + 24);

            String name = descriptorToName(typeString(b, dex, stringCache, stringOff, typeOff, typeIdx));
            String superName = superIdx == NO_INDEX ? null
                    : descriptorToName(typeString(b, dex, stringCache, stringOff, typeOff, superIdx));
            List<String> ifaces = new ArrayList<>();
            if (ifaceOff != 0) {
                check(dex, ifaceOff, 4);
                int n = b.getInt(ifaceOff);
                check(dex, ifaceOff + 4, 2L * n);
                for (int k = 0; k < n; k++) {
                    int t = b.getShort(ifaceOff + 4 + 2 * k) & 0xFFFF;
                    ifaces.add(descriptorToName(typeString(b, dex, stringCache, stringOff, typeOff, t)));
                }
            }
            List<String> methods = new ArrayList<>();
            boolean noArgCtor = false;
            if (dataOff != 0) {
                int[] cursor = {dataOff};
                int staticFields = uleb(dex, cursor);
                int instanceFields = uleb(dex, cursor);
                int directMethods = uleb(dex, cursor);
                int virtualMethods = uleb(dex, cursor);
                for (int f = 0; f < staticFields + instanceFields; f++) {
                    uleb(dex, cursor);
                    uleb(dex, cursor);
                }
                int[] counts = {directMethods, virtualMethods};
                for (int group = 0; group < 2; group++) {
                    int idx = 0;
                    for (int m = 0; m < counts[group]; m++) {
                        idx += uleb(dex, cursor);
                        int macc = uleb(dex, cursor);
                        uleb(dex, cursor); // code offset
                        if (idx < 0 || idx >= methodCount) {
                            throw new IOException("bad method index");
                        }
                        int nameIdx = b.getInt(methodOff + 8 * idx + 4);
                        String mname = string(b, dex, stringCache, stringOff, nameIdx);
                        int protoIdx = b.getShort(methodOff + 8 * idx + 2) & 0xFFFF;
                        methods.add(mname + "(");
                        if ("<init>".equals(mname) && (macc & 0x0001) != 0 && isNoArg(b, dex, stringCache, protoIdx)) {
                            noArgCtor = true;
                        }
                    }
                }
            }
            out.add(new ClassInfo(name, superName, ifaces, methods, (access & 0x0001) != 0,
                    (access & 0x0400) != 0, (access & 0x0200) != 0, noArgCtor));
        }
        return out;
    }

    private static boolean isNoArg(ByteBuffer b, byte[] dex, Map<Integer, String> cache, int protoIdx) {
        // header: proto_ids_size at 72, proto_ids_off at 76; each proto_id is 12 bytes.
        int protoCount = b.getInt(72);
        int protoOff = b.getInt(76);
        if (protoIdx < 0 || protoIdx >= protoCount) {
            return false;
        }
        return b.getInt(protoOff + 12 * protoIdx + 8) == 0; // parameters_off == 0 means no parameters
    }

    private static void check(byte[] dex, long off, long len) throws IOException {
        if (off < 0 || len < 0 || off + len > dex.length) {
            throw new IOException("dex table is outside the file");
        }
    }

    private static String typeString(ByteBuffer b, byte[] dex, Map<Integer, String> cache, int stringOff, int typeOff,
            int typeIdx) throws IOException {
        check(dex, typeOff + 4L * typeIdx, 4);
        return string(b, dex, cache, stringOff, b.getInt(typeOff + 4 * typeIdx));
    }

    private static String string(ByteBuffer b, byte[] dex, Map<Integer, String> cache, int stringOff, int idx)
            throws IOException {
        String cached = cache.get(idx);
        if (cached != null) {
            return cached;
        }
        check(dex, stringOff + 4L * idx, 4);
        int[] cursor = {b.getInt(stringOff + 4 * idx)};
        check(dex, cursor[0], 1);
        uleb(dex, cursor); // length in UTF-16 units
        int start = cursor[0];
        int end = start;
        while (end < dex.length && dex[end] != 0) {
            end++;
        }
        String s = new String(dex, start, end - start, UTF8);
        cache.put(idx, s);
        return s;
    }

    private static int uleb(byte[] dex, int[] cursor) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= dex.length || shift > 28) {
                throw new IOException("bad LEB128 value");
            }
            int v = dex[cursor[0]++] & 0xFF;
            result |= (v & 0x7F) << shift;
            if ((v & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    /** "Lcom/example/Foo;" to "com.example.Foo". Array and primitive descriptors are returned unchanged. */
    static String descriptorToName(String d) {
        if (d.length() > 2 && d.charAt(0) == 'L' && d.charAt(d.length() - 1) == ';') {
            return d.substring(1, d.length() - 1).replace('/', '.');
        }
        return d;
    }
}
