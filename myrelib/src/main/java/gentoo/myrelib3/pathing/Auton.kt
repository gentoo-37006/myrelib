package gentoo.myrelib3.pathing

/**
 * Marks the base class whose direct subclasses define alliance-neutral FTC autons.
 *
 * The myrelib KSP processor generates Blue and Red FTC opmode wrappers for each direct
 * subclass. Indirect subclasses are deliberately ignored.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Auton
