package net.corda.djvm.analysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ClassResolverTest {

    private val resolver = ClassResolver(Whitelist.DETERMINISTIC_RUNTIME, emptySet(), "sandbox/")

    @Test
    fun `can resolve class name`() {
        assertThat(resolver.resolve("")).isEqualTo("")
        assertThat(resolver.resolve("java/lang/Object")).isEqualTo("java/lang/Object")
        assertThat(resolver.resolve("java/lang/String")).isEqualTo("java/lang/String")
        assertThat(resolver.resolve("foo/bar/Test")).isEqualTo("sandbox/foo/bar/Test")
    }

    @Test
    fun `can resolve class name for arrays`() {
        assertThat(resolver.resolve("[Ljava/lang/Object;")).isEqualTo("[Ljava/lang/Object;")
        assertThat(resolver.resolve("[Ljava/lang/String;")).isEqualTo("[Ljava/lang/String;")
        assertThat(resolver.resolve("[Lfoo/bar/Test;")).isEqualTo("[Lsandbox/foo/bar/Test;")
        assertThat(resolver.resolve("[[Ljava/lang/Object;")).isEqualTo("[[Ljava/lang/Object;")
        assertThat(resolver.resolve("[[Ljava/lang/String;")).isEqualTo("[[Ljava/lang/String;")
        assertThat(resolver.resolve("[[Lfoo/bar/Test;")).isEqualTo("[[Lsandbox/foo/bar/Test;")
        assertThat(resolver.resolve("[[[Ljava/lang/Object;")).isEqualTo("[[[Ljava/lang/Object;")
        assertThat(resolver.resolve("[[[Ljava/lang/String;")).isEqualTo("[[[Ljava/lang/String;")
        assertThat(resolver.resolve("[[[Lfoo/bar/Test;")).isEqualTo("[[[Lsandbox/foo/bar/Test;")
    }

    @Test
    fun `can resolve binary class name`() {
        assertThat(resolver.resolveNormalized("")).isEqualTo("")
        assertThat(resolver.resolveNormalized("java.lang.Object")).isEqualTo("java.lang.Object")
        assertThat(resolver.resolveNormalized("foo.bar.Test")).isEqualTo("sandbox.foo.bar.Test")
    }

    @Test
    fun `can resolve type descriptor`() {
        assertThat(resolver.resolveDescriptor("")).isEqualTo("")
        assertThat(resolver.resolveDescriptor("V")).isEqualTo("V")
        assertThat(resolver.resolveDescriptor("L")).isEqualTo("L")
        assertThat(resolver.resolveDescriptor("L;")).isEqualTo("L;")
        assertThat(resolver.resolveDescriptor("L;I")).isEqualTo("L;I")
        assertThat(resolver.resolveDescriptor("()V")).isEqualTo("()V")
        assertThat(resolver.resolveDescriptor("(I)V")).isEqualTo("(I)V")
        assertThat(resolver.resolveDescriptor("(IJ)V")).isEqualTo("(IJ)V")
        assertThat(resolver.resolveDescriptor("Ljava/lang/Object;")).isEqualTo("Ljava/lang/Object;")
        assertThat(resolver.resolveDescriptor("Lcom/somewhere/Hello;")).isEqualTo("Lsandbox/com/somewhere/Hello;")
        assertThat(resolver.resolveDescriptor("JLFoo;LBar;I")).isEqualTo("JLsandbox/Foo;Lsandbox/Bar;I")
        assertThat(resolver.resolveDescriptor("(LFoo;)LBar;")).isEqualTo("(Lsandbox/Foo;)Lsandbox/Bar;")
    }

}
