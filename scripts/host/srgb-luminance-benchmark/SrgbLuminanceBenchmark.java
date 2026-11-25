import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import smallthingz.ui.controls.SelectionContrast;
import smallthingz.ui.controls.SelectionContrastKt;

/** Finite HotSpot-only experiment. No Android dependencies, JMH, or external libraries. */
public final class SrgbLuminanceBenchmark {
    public static final class SizeAgent {
        static Instrumentation instrumentation;
        public static void premain(String ignored, Instrumentation value) { instrumentation = value; }
    }
    private static volatile long sink;
    private static final ThreadMXBean METER = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().getId();
    private static final int[] COLORS = new int[4096];
    private static final int[] SURFACES = {0xfffafafa, 0xff181818, 0xff454349, 0xfff75815};
    private static final int[] PREFERRED = {-1, 0xff182950, 0xffb5b0b9, 0xff3f0166};
    private static final int[] INDICATION = {0x28ffffff, 0x28000000, 0x28b8a4eb, 0x2885c854};
    private static SelectionContrast[] policies;

    private static long allocated() { return METER.getThreadAllocatedBytes(THREAD); }
    private static void prepare() {
        if (!METER.isThreadAllocatedMemorySupported()) throw new AssertionError("No allocation meter");
        METER.setThreadAllocatedMemoryEnabled(true);
        int x = 0x5a17c9e3;
        for (int i = 0; i < COLORS.length; i++) {
            x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
            COLORS[i] = i < 256 ? 0xff000000 | (i * 0x010101) : x;
        }
        for (int i = 0; i < 2000; i++) sink ^= allocated() ^ System.nanoTime();
    }
    private static void metric(String stage, long ns, long bytes, long checksum, int calls) {
        System.out.printf("{\"stage\":\"%s\",\"ns\":%d,\"bytes\":%d,\"checksum\":\"%016x\",\"calls\":%d}%n",
                stage, ns, bytes, checksum, calls);
    }
    private static void emptyControl() {
        // Same counter/timer boundary; fixed instrumentation overhead, not subtracted.
        long a = allocated(), t = System.nanoTime();
        long elapsed = System.nanoTime() - t, bytes = allocated() - a;
        metric("empty_boundary", elapsed, bytes, 0, 1);
    }
    private static void layout(Class<?> type) throws Exception {
        int count = 0;
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() != double[].class || !Modifier.isStatic(f.getModifiers())) continue;
            count++;
            if (!Modifier.isPrivate(f.getModifiers()) || !Modifier.isFinal(f.getModifiers()))
                throw new AssertionError("Table must be private static final");
            f.setAccessible(true);
            double[] table = (double[]) f.get(null);
            if (table.length != 256 || table != f.get(null)) throw new AssertionError("Table shape/identity");
            long checksum = 0;
            for (int i = 0; i < 256; i++) {
                double v = i / 255.0;
                double expected = v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
                long bits = Double.doubleToRawLongBits(table[i]);
                if (bits != Double.doubleToRawLongBits(expected)) throw new AssertionError("Channel " + i);
                checksum += bits;
            }
            long size = SizeAgent.instrumentation.getObjectSize(table);
            System.out.printf("{\"stage\":\"table_layout\",\"field\":\"%s\",\"entries\":256,\"payload_bytes\":2048,\"shallow_and_primitive_graph_bytes\":%d,\"overhead_bytes\":%d,\"checksum\":\"%016x\"}%n",
                    f.getName(), size, size - 2048, checksum);
        }
        if (count > 1) throw new AssertionError("Multiple tables");
        System.out.printf("{\"stage\":\"table_count\",\"count\":%d}%n", count);
        HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        for (String option : new String[]{"UseCompressedOops", "UseCompressedClassPointers", "ObjectAlignmentInBytes"})
            System.out.printf("{\"stage\":\"vm_layout\",\"option\":\"%s\",\"value\":\"%s\"}%n", option, vm.getVMOption(option).getValue());
    }
    private static void coldFirstUse() {
        emptyControl();
        // Target is not explicitly preloaded, reflected, or initialized. Includes lazy resolution,
        // class loading/linkage as performed by this VM, <clinit>, and the first formula call.
        long a = allocated(), t = System.nanoTime();
        double value = SelectionContrastKt.srgbLuminance(0xff3458a0);
        long ns = System.nanoTime() - t, bytes = allocated() - a;
        sink = Double.doubleToRawLongBits(value);
        metric("first_use_including_lazy_loading", ns, bytes, sink, 1);
    }
    private static void coldInit() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        // Warm only JDK math and the initialization API, never the target class.
        sink = Double.doubleToRawLongBits(Math.pow(.123, 2.4));
        for (int i = 0; i < 2000; i++) lookup.ensureInitialized(Integer.class);
        emptyControl();
        long a = allocated(), t = System.nanoTime();
        Class<?> type = Class.forName("smallthingz.ui.controls.SelectionContrastKt", false,
                SrgbLuminanceBenchmark.class.getClassLoader());
        long ns = System.nanoTime() - t, bytes = allocated() - a;
        metric("load_without_initialization", ns, bytes, 0, 1);
        a = allocated(); t = System.nanoTime();
        type.getDeclaredMethods(); type.getDeclaredFields();
        ns = System.nanoTime() - t; bytes = allocated() - a;
        metric("reflection_metadata_before_init", ns, bytes, 0, 1);
        a = allocated(); t = System.nanoTime();
        lookup.ensureInitialized(Integer.class);
        ns = System.nanoTime() - t; bytes = allocated() - a;
        metric("initialized_class_control", ns, bytes, 0, 1);
        a = allocated(); t = System.nanoTime();
        lookup.ensureInitialized(type);
        ns = System.nanoTime() - t; bytes = allocated() - a;
        metric("runtime_initialization", ns, bytes, 0, 1);
        a = allocated(); t = System.nanoTime();
        double value = SelectionContrastKt.srgbLuminance(0xff3458a0);
        ns = System.nanoTime() - t; bytes = allocated() - a;
        sink = Double.doubleToRawLongBits(value);
        metric("first_direct_call_after_init_including_linkage", ns, bytes, sink, 1);
        layout(type); // Reflection/Instrumentation size inspection is after every cold measurement.
    }
    private static long luminance(int calls) {
        long checksum = 0;
        for (int i = 0; i < calls; i++) checksum += Double.doubleToRawLongBits(
                SelectionContrastKt.srgbLuminance(COLORS[i & 4095]));
        return checksum;
    }
    private static long indication(int calls) {
        long checksum = 0;
        for (int i = 0; i < calls; i++) {
            int p = i & 3;
            checksum += SelectionContrastKt.selectionForegroundWithIndication(
                    COLORS[i & 4095], SURFACES[p], PREFERRED[p], INDICATION[p]);
        }
        return checksum;
    }
    private static long foreground(int calls) {
        long checksum = 0;
        for (int i = 0; i < calls; i++) {
            SelectionContrast policy = policies[i & 3];
            int ink = policy.foreground(COLORS[i & 4095], (i & 1) == 0);
            checksum += ((long) policy.getIndicationColor() << 32) | (ink & 0xffffffffL);
        }
        return checksum;
    }
    private static long run(String name, int calls) {
        return switch (name) {
            case "srgbLuminance" -> luminance(calls);
            case "selectionForegroundWithIndication" -> indication(calls);
            case "SelectionContrast.foreground" -> foreground(calls);
            default -> throw new AssertionError(name);
        };
    }
    private static void hot() {
        policies = new SelectionContrast[4];
        for (int i = 0; i < policies.length; i++) policies[i] = new SelectionContrast(
                SURFACES[i], 0xff3458a0, INDICATION[i] | 0xff000000, PREFERRED[i], 0xff606060);
        String[] names = {"srgbLuminance", "selectionForegroundWithIndication", "SelectionContrast.foreground"};
        int[] calls = {524288, 16384, 65536};
        for (int k = 0; k < names.length; k++) {
            for (int i = 0; i < 8; i++) sink = run(names[k], calls[k]);
            emptyControl();
            for (int i = 0; i < 7; i++) {
                long a = allocated(), t = System.nanoTime();
                long checksum = run(names[k], calls[k]);
                long ns = System.nanoTime() - t, bytes = allocated() - a;
                sink = checksum;
                metric(names[k], ns, bytes, checksum, calls[k]);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        prepare();
        switch (args[0]) {
            case "first" -> coldFirstUse();
            case "init" -> coldInit();
            case "hot" -> hot();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
