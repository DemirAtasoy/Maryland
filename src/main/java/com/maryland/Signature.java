package com.maryland;

import java.io.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 *  Represents a File Signature (aka. Magic Number), which is the sequence of 4 bytes at the start of some
 *  file formats denoting the format of the file.
 *
 *  This class only explicitly defines Signatures which are relevant to the implementation Maryland -- Signatures for
 *  currently unimplemented file formats may be added as necessary.
 *
 *  Use {@link #of(File)} or {@link #of(byte[])} to retrieve the Signature of a file or array of bytes.
 */
final class Signature {

    public static final Signature ARCHIVE = new Signature(0x504B0304);
    public static final Signature CLASS = new Signature(0xCAFEBABE);

    private Integer signature;

    public int getSignature() {
        return this.signature;
    }

    Signature(final Integer signature) {
        this.signature = signature;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object instanceof Signature) {
            final Signature instance = (Signature) object;
            return Objects.equals(this.signature, instance.signature);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return signature;
    }

    @Override
    public String toString() {
        return Integer.toUnsignedString(this.signature, 16);
    }

    public static Optional<Signature> of(final Path file) throws IOException {
        return Signature.of(file.toFile());
    }

    /**
     *  Returns the File Signature (aka. Magic Number) of the specified File.
     *  It should be noted that this operation involves opening the file for reading. If the contents of the file are
     *  available to the caller, it may be preferable to use the {@link #of(byte[])} method instead.
     *
     * @param file The File to examine
     * @return The File Signature (aka. Magic Number)
     */
    public static Optional<Signature> of(final File file) throws IOException {
        Objects.requireNonNull(file);
        try (final InputStream inputstream = new FileInputStream(file)) {
            return Signature.of(inputstream);
        }
    }

    public static Optional<Signature> of(final InputStream inputstream) throws IOException {
        final byte[] bytes = new byte[4];
        for (int i = inputstream.read(bytes, 0, 4); i < 4; i++) {
            bytes[3] = bytes[2];
            bytes[2] = bytes[1];
            bytes[1] = bytes[0];
            bytes[0] = 0;
        }
        return Signature.of(bytes);
    }

    /**
     *  Returns the File Signature (aka. Magic Number) of the specified array of bytes
     *
     * @param bytes The array of bytes to examine
     * @return The File Signature (aka. Magic Number)
     */
    public static Optional<Signature> of(final byte[] bytes) {
        Objects.requireNonNull(bytes);

        /* Coalesce the first four bytes of the array into an int */
        int signature = bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);

        return of(signature);
    }

    public static Optional<Signature> of(final int signature) {
        /* 0x504B0304 is the File signature for JAR Archives */
        if (signature == ARCHIVE.getSignature()) {
            return Optional.of(ARCHIVE);
        }

        /* 0xCAFEBABE is the File signature for Java Class Files */
        if (signature == CLASS.getSignature()) {
            return Optional.of(CLASS);
        }

        /* Other signatures may be added to this class as necessary */
        return Optional.of(new Signature(signature));
    }

}
