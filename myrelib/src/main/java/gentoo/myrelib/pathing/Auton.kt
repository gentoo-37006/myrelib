package gentoo.myrelib.pathing

/**
 * Marks the base class whose direct subclasses define alliance-neutral FTC autons.
 *
 * The myrelib KSP processor generates Blue and Red FTC opmode wrappers in each direct
 * subclass's package. Indirect subclasses are deliberately ignored.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Auton
