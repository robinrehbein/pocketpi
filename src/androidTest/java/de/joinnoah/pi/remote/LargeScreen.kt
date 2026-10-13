package de.joinnoah.pi.remote

/**
 * Marks the instrumentation tests that CI also runs on the Pixel Tablet managed device
 * (`pixelTabletApi36`), filtered with the runner's `annotation` argument. Keep the set small: every
 * test here runs on a second emulator. The phone device runs them too.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class LargeScreen
