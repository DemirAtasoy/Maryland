package com.maryland;

import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import net.lingala.zip4j.ZipFile;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The <code>Plugin</code> class represents plugins that have been loaded into the JVM from a JAR archive.
 *  An instance of the <code>Plugin</code> class acts as an interface through which the application can interface
 *  with the plugin.<br><br>
 *
 *  Instances of this class are initialized with the {@link #load(Path...)} or associated method.<br><br>
 *
 *  Plugin archives must possess the following properties to be properly handled by Maryland;
 *  <ul>
 *      <li>Comprising a valid JAR archive with a .jar file extension</li>
 *      <li>Allowed to be opened for reading by the current process</li>
 *      <li>Containing only class files which are in the proper format and able to be parsed by the classloader</li>
 *  </ul>
 *  <br>
 *  The primary method of interfacing with a <code>Plugin</code> is through posting of events, which are dispatched
 *  to methods denoted by the plugin to receive events of that type by an annotation (default {@link Subscribe}).
 *  See {@link 	#post(Object)} for more information on event dispatching. <br><br>
 *
 */
public abstract class Plugin {

    private final Map<Class<?>, Collection<Consumer<Object>>> eventmap = new HashMap<>();

    /*
     *  Instances of this class are generated and instantiated dynamically by Maryland.
     *  This class must not be subclassed or instantiated manually
     */
    protected Plugin() throws Error {
        if (this.getClass().getClassLoader().getClass() != Plugin.ClassLoader.class) throw new InstantiationError();
    }

    /**
     * Returns the Plugin's {@link UUID}
     *
     * @return The UUID of this Plugin
     */
    public UUID getUUID() {
        return null;
    }

    /**
     *  Posts an event to this Plugin. The event will be received in an unspecified order by all valid Event Handlers
     *  defined by the Plugin for which the posted event object is assignable.
     *
     *  An Event Handler is considered valid if and only if:
     *  <ul>
     *      <li>It is annotated with the {@link Subscribe} annotation</li>
     *      <li>It is Static</li>
     *      <li>It's visibility is Public</li>
     *      <li>It accepts exactly one argument, which is not of a primitive or array type</li>
     *  </ul>
     *
     * @param event The event to be posted
     */
    public void post(final Object event) {

        /*
         *  Null events, while in line with the Dispatching Model, would unnecessarily burden Event Handlers with
         *  checking their arguments for null values and provide little or no benefit in return.
         */
        Objects.requireNonNull(event);

        new InheritanceIterator(event.getClass()).forEachRemaining(class_ -> {
            final Collection<Consumer<Object>> consumers = this.eventmap.get(class_);
            if (consumers != null) {
                consumers.forEach(consumer -> consumer.accept(event));
            }
        });
    }

    public static Optional<Plugin> getContext(final Class<?> class_) {
        final java.lang.ClassLoader classloader = class_.getClassLoader();
        if (classloader instanceof Plugin.ClassLoader) {
            return Optional.of(Plugin.ClassLoader.cast(classloader).asPlugin());
        }
        return Optional.empty();
    }

    /**
     * @see #load(Path...);
     * @param files The files to be loaded
     * @return A Collection containing {@link Plugin} instances for each plugin which could be loaded
     */
    public static Collection<Plugin> load(final File... files) {
        Objects.requireNonNull(files);
        Arrays.stream(files).forEach(Objects::requireNonNull);

        final Path[] files_ = new Path[files.length];
        for (int i = 0; i < files.length; i++) {
            files_[i] = files[i].toPath();
        }
        return Plugin.load(files_);
    }

    /**
     *  Recursively loads all JAR archives at or under the specified files into the JVM, performing as required
     *  initialization subroutines for registering Event Handlers and other functionality
     *  Returns a {@link Collection} containing {@link Plugin} handles for each loaded Plugin, which may be used to
     *  interface with them as required.
     *
     *  A new {@link java.lang.ClassLoader} is created for each loaded Plugin, which is used to load its classes as well
     *  as its associated {@link Plugin} handle object in the {@link Collection} returned by this method.
     *  Classes loaded into the JVM by this method will become eligible for collection by the GC at the same time as
     *  their associated {@link Plugin} object, provided no references are maintained to class(s) loaded by the Plugin's
     *  ClassLoader
     *
     * @param files The list of files to be searched for Plugins
     * @return A collection containing Plugin handles for each loaded plugin
     */
    public static Collection<Plugin> load(final Path... files) {
        Objects.requireNonNull(files);
        Arrays.stream(files).forEach(Objects::requireNonNull);

        final Path[] files_ = new Path[files.length];
        for(int i = 0; i < files.length; i++) {
            files_[i] = files[i].normalize().toAbsolutePath();
        }

        final Collection<Path> archives = new HashSet<>();
        for (final Path file : files_) {
            if (!Files.exists(file)) {
                Maryland.getLog().log(Level.WARNING, "Unable to find " + file.toString() + ": File does not exist");
                continue;
            }
            final boolean isRegularFile = Files.isRegularFile(file);
            final boolean isDirectory = Files.isDirectory(file);
            if (!isRegularFile && isDirectory) {
                try (final Stream<Path> stream = Files.walk(file)) {
                    stream  .filter(file_ -> Files.exists(file_))
                            .filter(file_ -> Files.isRegularFile(file_))
                            .filter(file_ -> !Files.isDirectory(file_))
                            .filter(file_ -> getFileExtension(file_).equalsIgnoreCase(".jar"))
                            .forEach(archives::add);
                }
                catch (final IOException exception) {
                    Maryland.getLog().log(Level.WARNING, exception.getMessage());
                    continue;
                }
            }
            if (isRegularFile && !isDirectory && getFileExtension(file).equalsIgnoreCase(".jar")) {
                archives.add(file);
            }
        }

        final Collection<Plugin> plugins = new ArrayList<>();
        for (final Path archive : archives) {
            try {
                plugins.add(Plugin.load_(archive));
            }
            catch (final IOException exception) {
                Maryland.getLog().log(Level.SEVERE, exception, () -> "");
            }
        }
        return plugins;
    }

    private static Plugin load_(final Path file) throws IOException {
        Plugin.requireValidArchive(file);
        final Path file_ = file.normalize().toAbsolutePath();

        /*
         *  A new ClassLoader is created for each loaded Plugin which is used to load all of that Plugin's classes,
         *  as well as its Handle Object. This allows a Plugin's classes to become eligible for collection by the GC
         *  at the same time as its Handle Object, provided no instances of classes defined by the Plugin are kept
         *  reachable by the host application
         */

        final Plugin.ClassLoader classloader;
        try {
            classloader = new Plugin.ClassLoader(file_);
        }
        catch (final ReflectiveOperationException exception) {
            Maryland.getLog().log(Level.SEVERE, "ReflectiveOperationException whilst initializing Plugin ClassLoader; This shouldn't have happened");
            Maryland.getLog().log(Level.SEVERE, exception, () -> "");
            System.exit(-1);
            return null;
        }

        /*
         *  The JAR Archive is extracted to a temporary directory, which is deleted after loading has been completed
         */

        final Path directory = Files.createTempDirectory("").normalize().toAbsolutePath();
        try {
            new ZipFile(file_.toFile()).extractAll(directory.toString());

            final Collection<Path> files;
            try (final Stream<Path> stream = Files.walk(directory)) {
                files = stream
                        .filter(f -> Files.exists(f))
                        .filter(f -> Files.isRegularFile(f))
                        .filter(f -> !Files.isDirectory(f))
                        .filter(f -> getFileExtension(f).equalsIgnoreCase(".class"))
                        .collect(Collectors.toList());
            }
            for (final Path classfile : files) {
                classloader.getClassData().put(getClassName(directory, classfile), Files.readAllBytes(classfile));
            }
        }
        finally {
            try {
                deleteRecursively(directory);
            }
            catch (final IOException exception) {
                Maryland.getLog().log(Level.WARNING, exception.getMessage());
            }
        }

        for (final Map.Entry<String, byte[]> entry : classloader.getClassData().entrySet()) {
            final Class<?> class_ = classloader.define(entry.getKey(), entry.getValue());
        }
        classloader.getClassData().clear();

        return classloader.asPlugin();
    }

    private static void requireValidArchive(final Path file) throws IOException {
        Objects.requireNonNull(file);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("Unable to find " + file.toString() + ": File does not exist");
        }
        if (!Files.isReadable(file)) {
            throw new IOException("Unable to read " + file.toString() + ": File is unreadable");
        }
        if (!Files.isRegularFile(file) || Files.isDirectory(file)) {
            throw new IOException("Unable to load " + file.toString() + ": File is a directory or is otherwise not a Regular File");
        }
        if (!getFileExtension(file).equalsIgnoreCase(".jar")) {
            throw new IOException("Unable to load " + file.toString());
        }
        if (Signature.of(file).orElse(null) != Signature.ARCHIVE) {
            throw new IOException("Unable to load " + file.toString() + ": Not a JAR Archive");
        }
    }

    private static void deleteRecursively(final Path directory) throws IOException {
        Objects.requireNonNull(directory);
        final boolean success;
        try (Stream<Path> stream = Files.walk(directory)) {
            success = stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .allMatch(File::delete);
        }
        if (!success) {
            throw new IOException("Unable to delete temporary directory " + directory.toString());
        }
    }

    private static final class ClassLoader extends java.lang.ClassLoader {
        public static ClassLoader cast(final java.lang.ClassLoader instance) {
            return (ClassLoader) instance;
        }

        private final Path archive;
        private final Plugin plugin;

        private final Map<String, byte[]> classdata = new Object2ObjectRBTreeMap<>();
        private final Map<String, Class<?>> classmap = new Object2ObjectRBTreeMap<>();

        public ClassLoader(final Path archive) throws ReflectiveOperationException {
            this.archive = archive;
            final UUID uuid = UUID.randomUUID();

            final ClassNode classnode = new ClassNode();
            classnode.version = 46;
            classnode.name = "Plugin$" + uuid.toString().replace("-", "");
            classnode.superName = Type.getInternalName(Plugin.class);
            classnode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;

            final MethodNode methodnode0 = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, new String[0]);
            methodnode0.instructions.add(new InsnList() {{
                this.add(new VarInsnNode(Opcodes.ALOAD, 0));
                this.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(Plugin.class), "<init>", "()V"));
                this.add(new InsnNode(Opcodes.RETURN));
            }});
            classnode.methods.add(methodnode0);

            final MethodNode methodnode1 = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "getUUID", "()L" + Type.getInternalName(UUID.class) + ";", null, new String[0]);
            methodnode1.instructions.add(new InsnList() {{
                this.add(new LdcInsnNode(uuid.toString()));
                this.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(UUID.class), "fromString", "(L" + Type.getInternalName(String.class) + ";)L" + Type.getInternalName(UUID.class) + ";"));
                this.add(new InsnNode(Opcodes.ARETURN));
            }});
            classnode.methods.add(methodnode1);

            final ClassWriter classwriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            classnode.accept(classwriter);
            final byte[] bytes = classwriter.toByteArray();

            this.plugin = (Plugin) this.define(bytes).newInstance();
        }

        public Plugin asPlugin() {
            return this.plugin;
        }

        private Map<String, byte[]> getClassData() {
            return this.classdata;
        }

        private Map<String, Class<?>> getClassMap() {
            return Collections.unmodifiableMap(this.classmap);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final Class<?> class_ = this.classmap.get(name);
            if (class_ != null) {
                return class_;
            }
            final byte[] bytes = this.classdata.get(name);
            if (bytes != null) {
                return this.define(name, bytes);
            }
            throw new ClassNotFoundException();
        }

        public Class<?> define(final byte[] bytes) {
            return this.define(null, bytes);
        }

        public Class<?> define(final String name, final byte[] bytes) {
            if (name != null) {
                final Class<?> class__ = this.classmap.get(name);
                if (class__ != null) {
                    return class__;
                }
            }
            final Class<?> class_ = this.defineClass(name, bytes, 0, bytes.length);
            this.classmap.put(class_.getName(), class_);

            try {
                this.onClassDefined(class_);
            }
            catch (final ReflectiveOperationException exception) {
                Maryland.getLog().log(Level.SEVERE, "ReflectiveOperationException whilst registering Event Handlers for " + class_.getName() + "; This shouldn't have happened");
                Maryland.getLog().log(Level.SEVERE, exception, () -> "");
                System.exit(-1);
                return null;
            }

            return class_;
        }

        private void onClassDefined(final Class<?> class_) throws IllegalAccessException, InstantiationException {
            final Method[] methods = class_.getDeclaredMethods();
            for (final Method method : methods) {
                final Subscribe annotation = method.getDeclaredAnnotation(Subscribe.class);
                if (annotation == null) {
                    continue;
                }
                if (!Modifier.isStatic(method.getModifiers())) {
                    Maryland.getLog().warning("Non-static method " + toString(method) + " annotated with " + Subscribe.class.getName());
                    continue;
                }
                final Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 1) {
                    // TODO: Implement multiple event parameters
                    Maryland.getLog().warning("Event Handler " + toString(method) + " must accept exactly one parameter (was" + parameterTypes.length + ")");
                    continue;
                }
                final Class<?> type = parameterTypes[0];
                if (type.isPrimitive()) {
                    // TODO: Implement primitive event types
                    Maryland.getLog().warning("Event handler " + toString(method) + " may not take a primitive argument");
                    continue;
                }
                if (type.isArray()) {
                    // TODO: Implement Array event types
                    Maryland.getLog().warning("Event handler " + toString(method) + " may not take an array argument");
                    continue;
                }

                final ClassNode classnode = new ClassNode();
                classnode.version = 52;
                classnode.name = "Plugin$" + this.asPlugin().getUUID().toString().replace("-", "") + "$" + UUID.randomUUID().toString().replace("-", "");
                classnode.superName = Type.getInternalName(Object.class);
                classnode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
                classnode.interfaces.add(Type.getInternalName(Consumer.class));

                final MethodNode methodnode0 = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, new String[0]);
                methodnode0.instructions.add(new InsnList() {{
                    this.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    this.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V"));
                    this.add(new InsnNode(Opcodes.RETURN));
                }});
                classnode.methods.add(methodnode0);

                final MethodNode methodnode1 = new MethodNode(Opcodes.ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
                methodnode1.instructions.add(new InsnList() {{
                    this.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    this.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getName().replace('.', '/')));
                    this.add(new InsnNode(Opcodes.DUP));
                    this.add(new MethodInsnNode(Opcodes.INVOKESTATIC, class_.getName().replace('.', '/'), method.getName(), getDescriptor(method.getReturnType(), type), false));
                    this.add(new InsnNode(Opcodes.RETURN));
                }});
                classnode.methods.add(methodnode1);

                final ClassWriter classwriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                classnode.accept(classwriter);
                final byte[] bytes = classwriter.toByteArray();

                final Consumer<Object> consumer = (Consumer<Object>) this.define(bytes).newInstance();
                this.asPlugin().eventmap.computeIfAbsent(type, class__ -> new ArrayList<>(4)).add(consumer);
            }
        }

        // TODO: Implement JAR Resources

        private static String toString(final Method method) {
            StringBuilder stringbuilder = new StringBuilder();
            stringbuilder.append(method.getDeclaringClass().getName());
            stringbuilder.append('.');
            stringbuilder.append(method.getName());
            stringbuilder.append('(');
            for (Class<?> class_ : method.getParameterTypes()) {
                stringbuilder.append(class_.getName()).append(",");
            }
            stringbuilder.append(')');
            return stringbuilder.toString();
        }

        private static String getDescriptor(final Class<?> returnType, final Class<?> type) {
            final String returnTypeName = denormalize(returnType.getName());
            final String typeName = denormalize(type.getName());
            return "(" + ("V".equals(typeName) ? "" : typeName) + ")" + returnTypeName;
        }

        private static String denormalize(final String typeName) {
            Objects.requireNonNull(typeName);
            if (typeName.length() == 0) {
                return "";
            }
            if (typeName.charAt(0) == '[') {
                return typeName;
            }
            if ("void".equals(typeName)) {
                return "V";
            }
            if ("boolean".equals(typeName)) {
                return "Z";
            }
            if ("byte".equals(typeName)) {
                return "B";
            }
            if ("short".equals(typeName)) {
                return "S";
            }
            if ("char".equals(typeName)) {
                return "C";
            }
            if ("int".equals(typeName)) {
                return "I";
            }
            if ("long".equals(typeName)) {
                return "J";
            }
            if ("float".equals(typeName)) {
                return "F";
            }
            if ("double".equals(typeName)) {
                return "D";
            }
            return "L" + typeName.replace('.', '/') + ";";
        }

    }

    private static String getFileExtension(final Path file) {
        final String name = file.getFileName().toString();
        final int index = name.lastIndexOf('.');
        return (index > 0) ? name.substring(index) : "";
    }

    private static String getClassName(final Path root, final Path file) {
        final Path root_ = Objects.requireNonNull(root).normalize().toAbsolutePath();
        final Path file_ = Objects.requireNonNull(file).normalize().toAbsolutePath();

        String classname = file_.normalize().toAbsolutePath().toString().substring(root_.toString().length() + 1);
        classname = classname.substring(0, classname.length() - getFileExtension(file_).length());
        return classname.replace(File.separator, ".");
    }
}
