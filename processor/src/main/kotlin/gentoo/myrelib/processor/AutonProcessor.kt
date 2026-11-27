package gentoo.myrelib.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

class AutonProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        AutonProcessor(environment.codeGenerator, environment.logger)
}

private class AutonProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private val generatedClasses = mutableMapOf<String, String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val declarations = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val deferred = declarations.filterNot(KSAnnotated::validate)

        declarations.asSequence()
            .filter(KSAnnotated::validate)
            .filter { it.hasDirectAnnotatedAutonBase() }
            .forEach(::generateWrappers)

        return deferred
    }

    private fun KSClassDeclaration.hasDirectAnnotatedAutonBase(): Boolean =
        superTypes.any { type ->
            val directSupertype = type.resolve().declaration as? KSClassDeclaration
                ?: return@any false
            directSupertype.classKind == ClassKind.CLASS &&
                directSupertype.annotations.any { it.isAutonAnnotation() }
        }

    private fun KSAnnotation.isAutonAnnotation(): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == AUTON_ANNOTATION

    private fun generateWrappers(declaration: KSClassDeclaration) {
        val qualifiedName = declaration.qualifiedName?.asString()
        val containingFile = declaration.containingFile
        if(qualifiedName == null || containingFile == null) {
            logger.error("Alliance auton classes must be top-level source declarations", declaration)
            return
        }
        if(declaration.parentDeclaration != null) {
            logger.error("Alliance auton classes must be top-level", declaration)
            return
        }
        if(declaration.classKind != ClassKind.CLASS) {
            logger.error("Alliance autons must be classes", declaration)
            return
        }
        if(Modifier.PRIVATE in declaration.modifiers) {
            logger.error("Alliance auton classes must be visible to generated wrappers", declaration)
            return
        }
        if(Modifier.OPEN !in declaration.modifiers && Modifier.ABSTRACT !in declaration.modifiers) {
            logger.error("Alliance auton classes must be open so generated wrappers can extend them", declaration)
            return
        }

        val constructor = declaration.primaryConstructor
        val parameters = constructor?.parameters.orEmpty()
        val acceptsAlliance = parameters.size == 1 &&
            parameters.single().type.resolve().declaration.qualifiedName?.asString() == ALLIANCE_CLASS
        if(!acceptsAlliance) {
            logger.error(
                "Alliance auton classes must have exactly one constructor parameter of type $ALLIANCE_CLASS",
                declaration
            )
            return
        }
        if(constructor != null && Modifier.PRIVATE in constructor.modifiers) {
            logger.error("Alliance auton constructors must be visible to generated wrappers", constructor)
            return
        }

        val generatedPackage = declaration.packageName.asString()
        val baseName = declaration.simpleName.asString()
        ALLIANCE_NAMES.forEach { enumName ->
            val displayName = enumName.lowercase().replaceFirstChar(Char::uppercase)
            val generatedName = "${displayName}_$baseName"
            val generatedQualifiedName = if(generatedPackage.isEmpty()) generatedName
                else "$generatedPackage.$generatedName"
            val previousDeclaration = generatedClasses.putIfAbsent(generatedQualifiedName, qualifiedName)
            if(previousDeclaration != null && previousDeclaration != qualifiedName) {
                logger.error(
                    "$generatedQualifiedName would be generated for more than one auton class",
                    declaration
                )
                return@forEach
            }
            if(previousDeclaration == qualifiedName) return@forEach

            codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false, containingFile),
                packageName = generatedPackage,
                fileName = generatedName
            ).bufferedWriter().use { writer ->
                if(generatedPackage.isNotEmpty()) {
                    writer.appendLine("package $generatedPackage")
                    writer.appendLine()
                }
                writer.appendLine(
                    "@$FTC_AUTONOMOUS_ANNOTATION(" +
                        "group = \"$displayName\", preselectTeleOp = \"$displayName\")"
                )
                writer.appendLine("class $generatedName : $qualifiedName($ALLIANCE_CLASS.$enumName)")
            }
        }
    }
}
