package com.rewardadguard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the constructor contract of [MainViewModel].
 *
 * This looks like a tautology but it is not: the object is created by
 * `androidx.lifecycle.ViewModelProvider`, which uses the legacy
 * `NewInstanceFactory`-style path when no `SavedStateRegistry` is present and
 * `SavedStateViewModelFactory` when there is. Both resolve a constructor by
 * reflecting over the parameter types, and *neither* understands Kotlin default
 * arguments — a `SavedStateHandle` declared as
 * `handle: SavedStateHandle = SavedStateHandle()` compiles to
 * `(Application, SavedStateHandle, int, DefaultConstructorMarker)` from the JVM's
 * point of view, and is never matched. The ViewModel is then never constructed
 * and the app dies with `Could not find appropriate constructor`.
 *
 * Adding `SavedStateHandle` without a registry must also stay impossible to
 * mistake for a crash: the secondary constructor has to exist and has to work.
 */
class MainViewModelConstructorTest {

    @Test
    fun `the saved state constructor has no synthetic marker parameter`() {
        val twoArg = MainViewModel::class.java.constructors
            .filter { it.parameterTypes.size == 2 }
            .filter { it.parameterTypes[1] == androidx.lifecycle.SavedStateHandle::class.java }
            .toList()

        assertEquals(
            "expected exactly one (Application, SavedStateHandle) constructor",
            1,
            twoArg.size
        )

        // The 4-parameter synthetic constructor Kotlin emits for a parameter with
        // a default value looks like this — if it is the *only* candidate, the
        // factory above will not find it.
        val hasSyntheticDefault = MainViewModel::class.java.constructors.any { constructor ->
            constructor.parameterTypes.any { it.name.endsWith("DefaultConstructorMarker") }
        }
        assertTrue(
            "no DefaultConstructorMarker constructor may exist: the reflection-based " +
                "ViewModel factory would skip it, so MainViewModel could never be built",
            !hasSyntheticDefault
        )
    }

    @Test
    fun `an application-only constructor exists for tests and previews`() {
        val oneArg = MainViewModel::class.java.constructors
            .filter { it.parameterTypes.size == 1 }
            .toList()

        assertEquals(
            "expected a single (Application) convenience constructor",
            1,
            oneArg.size
        )
        assertNotNull(
            "the convenience constructor must be public",
            MainViewModel::class.java.getConstructor(android.app.Application::class.java)
        )
    }

    @Test
    fun `both constructors expose a usable saved state handle`() {
        // The handle is what carries the installed-app search term across process
        // death, so it must never be null and must round-trip a value.
        for (constructor in MainViewModel::class.java.constructors) {
            val parameterTypes = constructor.parameterTypes.map { it.name }
            assertTrue(
                "unexpected constructor $parameterTypes",
                parameterTypes.all {
                    it == "android.app.Application" || it == "androidx.lifecycle.SavedStateHandle"
                }
            )
        }

        val handle = androidx.lifecycle.SavedStateHandle()
        handle.set(MainViewModel.KEY_APP_QUERY, "shopee")
        assertEquals("shopee", handle.get<String>(MainViewModel.KEY_APP_QUERY))
    }
}