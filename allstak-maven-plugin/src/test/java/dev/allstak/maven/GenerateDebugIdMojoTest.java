package dev.allstak.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateDebugIdMojoTest {

    @Test
    void writesPropertiesFileWithRandomId(@TempDir Path tmp) throws Exception {
        GenerateDebugIdMojo mojo = new GenerateDebugIdMojo();
        Field f = GenerateDebugIdMojo.class.getDeclaredField("outputDirectory");
        f.setAccessible(true);
        f.set(mojo, tmp);

        mojo.execute();

        Path out = tmp.resolve("allstak-debug-meta.properties");
        assertThat(Files.isRegularFile(out)).isTrue();
        Properties p = new Properties();
        try (var is = Files.newInputStream(out)) { p.load(is); }
        assertThat(p.getProperty("debug.id")).hasSize(36); // UUID
    }

    @Test
    void respectsProvidedDebugId(@TempDir Path tmp) throws Exception {
        GenerateDebugIdMojo mojo = new GenerateDebugIdMojo();
        Field f = GenerateDebugIdMojo.class.getDeclaredField("outputDirectory");
        f.setAccessible(true);
        f.set(mojo, tmp);
        Field d = GenerateDebugIdMojo.class.getDeclaredField("debugId");
        d.setAccessible(true);
        d.set(mojo, "00000000-0000-0000-0000-000000000001");

        mojo.execute();

        Properties p = new Properties();
        try (var is = Files.newInputStream(tmp.resolve("allstak-debug-meta.properties"))) { p.load(is); }
        assertThat(p.getProperty("debug.id")).isEqualTo("00000000-0000-0000-0000-000000000001");
    }
}
